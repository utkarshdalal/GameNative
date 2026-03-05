package app.gamenative.utils

import android.content.res.AssetManager
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import timber.log.Timber
import kotlin.io.path.deleteExisting
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo

object FileUtils {

    /**
     * Calculate the total size of a directory recursively
     *
     * @param directory The directory to calculate size for
     * @return Total size in bytes
     */
    fun calculateDirectorySize(directory: File): Long {
        var size = 0L
        try {
            if (!directory.exists() || !directory.isDirectory) {
                return 0L
            }

            val files = directory.listFiles() ?: return 0L
            for (file in files) {
                size += if (file.isDirectory) {
                    calculateDirectorySize(file)
                } else {
                    file.length()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error calculating directory size for ${directory.name}")
        }
        return size
    }

    fun makeDir(dirName: String) {
        val homeItemsDir = File(dirName)
        homeItemsDir.mkdirs()
    }

    fun makeFile(fileName: String, errorTag: String? = "FileUtils", errorMsg: ((Exception) -> String)? = null) {
        try {
            val file = File(fileName)
            if (!file.exists()) {
                file.createNewFile()
            }
        } catch (e: Exception) {
            Timber.e("%s encountered an issue in makeFile()", errorTag)
            Timber.e(errorMsg?.invoke(e) ?: "Error creating file: $e")
        }
    }

    fun createPathIfNotExist(filepath: String) {
        val file = File(filepath)
        var dirs = filepath

        // if the file path is not a directory and if we're not at the root directory then get the parent directory
        if (!filepath.endsWith('/') && filepath.lastIndexOf('/') > 0) {
            dirs = file.parent!!
        }

        makeDir(dirs)
    }

    fun readFileAsString(path: String, errorTag: String = "FileUtils", errorMsg: ((Exception) -> String)? = null): String? {
        var fileData: String? = null
        try {
            val r = BufferedReader(FileReader(path))
            val total = StringBuilder()
            var line: String?

            while ((r.readLine().also { line = it }) != null) {
                total.append(line).append('\n')
            }

            fileData = total.toString()
        } catch (e: Exception) {
            Timber.e("%s encountered an issue in readFileAsString()", errorTag)
            Timber.e(errorMsg?.invoke(e) ?: "Error reading file: $e")
        }

        return fileData
    }

    fun writeStringToFile(data: String, path: String, errorTag: String? = "FileUtils", errorMsg: ((Exception) -> String)? = null) {
        createPathIfNotExist(path)

        try {
            val fOut = FileOutputStream(path)
            val myOutWriter = OutputStreamWriter(fOut)
            myOutWriter.append(data)
            myOutWriter.close()
            fOut.flush()
            fOut.close()
        } catch (e: Exception) {
            Timber.e("%s encounted an issue in writeStringToFile()", errorTag)
            Timber.e(errorMsg?.invoke(e) ?: "Error writing to file: $e")
        }
    }

    /**
     * Traverse through a directory and perform an action on each file
     *
     * @param rootPath The start path
     * @param maxDepth How deep to go in the directory tree, a value of -1 keeps going
     * @param action The action to perform on each file
     */
    fun walkThroughPath(rootPath: Path, maxDepth: Int = 0, action: (Path) -> Unit) {
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) return
        Files.list(rootPath).use { fileList ->
            fileList.forEach {
                action(it)
                if (maxDepth != 0 && it.exists() && it.isDirectory()) {
                    walkThroughPath(
                        rootPath = it,
                        maxDepth = if (maxDepth > 0) maxDepth - 1 else maxDepth,
                        action = action,
                    )
                }
            }
            fileList.close()
        }
    }

    fun findFiles(rootPath: Path, pattern: String, includeDirectories: Boolean = false): Stream<Path> {
        val patternParts = pattern.split("*").filter { it.isNotEmpty() }
        Timber.i("$pattern -> $patternParts")
        if (!Files.exists(rootPath)) return emptyList<Path>().stream()
        return Files.list(rootPath).filter { path ->
            if (path.isDirectory() && !includeDirectories) {
                false
            } else {
                val fileName = path.name
                Timber.i("Checking $fileName for pattern $pattern")
                var startIndex = 0
                !patternParts.map {
                    val index = fileName.indexOf(it, startIndex)
                    if (index >= 0) {
                        startIndex = index + it.length
                    }
                    index
                }.any { it < 0 }
            }
        }
    }

    fun findFilesRecursive(
        rootPath: Path,
        pattern: String,
        maxDepth: Int = -1,
        includeDirectories: Boolean = false,
    ): Stream<Path> {
        val patternParts = pattern.split("*").filter { it.isNotEmpty() }
        Timber.i("$pattern -> $patternParts (recursive, depth=$maxDepth)")
        if (!Files.exists(rootPath)) return emptyList<Path>().stream()

        val results = mutableListOf<Path>()

        fun matches(fileName: String): Boolean {
            var startIndex = 0
            for (part in patternParts) {
                val index = fileName.indexOf(part, startIndex)
                if (index < 0) return false
                startIndex = index + part.length
            }
            return true
        }

        walkThroughPath(rootPath, maxDepth) { path ->
            if (path.isDirectory()) {
                if (includeDirectories && matches(path.name)) {
                    results.add(path)
                }
            } else {
                val fileName = path.name
                Timber.i("Checking $fileName for pattern $pattern (recursive)")
                if (matches(fileName)) {
                    results.add(path)
                }
            }
        }

        return results.stream()
    }

    fun assetExists(assetManager: AssetManager, assetPath: String): Boolean {
        return try {
            assetManager.open(assetPath).use {
                true
            }
        } catch (e: IOException) {
            // Timber.e(e)
            false
        }
    }

    /**
     * Resolves a relative path against a base dir using case-insensitive matching for each segment.
     * Info file may list e.g. "checkapplication.exe" while the actual file is "CheckApplication.exe" (Linux/Android are case-sensitive).
     */
    fun findFileCaseInsensitive(baseDir: File, relativePath: String): File? {
        val segments = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        var current = baseDir
        for (segment in segments) {
            val match = current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: return null
            current = match
        }
        return current.takeIf { it.exists() }
    }

    /**
     * Walks a base directory and merges sibling directories that share a
     * case-insensitive name.
     */
    fun mergeCaseInsensitiveDirectories(basePath: Path) {
        if (!basePath.isDirectory()) return

        // 1. Get all directories at the current level
        val directories = basePath.listDirectoryEntries().filter { it.isDirectory() }

        // 2. Group them by case-insensitive name
        val groupedDirs = directories.groupBy { it.name.lowercase() }

        for ((_, dirGroup) in groupedDirs) {
            if (dirGroup.size > 1) {
                // A clash exists! Pick the first one as the main target
                val targetDir = dirGroup.first()
                val sourceDirs = dirGroup.drop(1)

                for (source in sourceDirs) {
                    mergeContents(source, targetDir)

                    // Once contents are safely moved, delete the empty source directory
                    if (source.listDirectoryEntries().isEmpty()) {
                        source.deleteExisting()
                    }
                }
                // 3. Recurse into the newly merged target directory
                mergeCaseInsensitiveDirectories(targetDir)
            } else {
                // No clash here, just continue walking down the tree
                mergeCaseInsensitiveDirectories(dirGroup.first())
            }
        }
    }

    /**
     * Helper function to recursively move contents from a source directory into a target directory,
     * resolving case-insensitive collisions along the way.
     */
    private fun mergeContents(source: Path, target: Path) {
        // Cache target entries by lowercase name for quick O(1) lookups
        val targetEntries = target.listDirectoryEntries().associateBy { it.name.lowercase() }

        for (sourceChild in source.listDirectoryEntries()) {
            val lowerName = sourceChild.name.lowercase()
            val matchingTarget = targetEntries[lowerName]

            if (matchingTarget != null) {
                // A case-insensitive match already exists in the target
                if (sourceChild.isDirectory() && matchingTarget.isDirectory()) {
                    // Both are directories: recursively merge them further down
                    mergeContents(sourceChild, matchingTarget)

                    // Delete the nested source directory if it's now empty
                    if (sourceChild.listDirectoryEntries().isEmpty()) {
                        sourceChild.deleteExisting()
                    }
                } else {
                    // File conflict (or file vs. directory conflict).
                    // Defaulting to overwriting the target with the source file.
                    sourceChild.moveTo(matchingTarget, overwrite = true)
                }
            } else {
                // No collision exists; simply move the file/directory over entirely
                val newDest = target.resolve(sourceChild.name)
                sourceChild.moveTo(newDest)
            }
        }
    }
}
