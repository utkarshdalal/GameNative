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

    fun matchesGlob(fileName: String, pattern: String): Boolean {
        if (pattern.isEmpty() || pattern == "*") return true
        // Pattern with no '*' is an exact (case-insensitive) match, not a substring search.
        if (!pattern.contains('*')) return fileName.equals(pattern, ignoreCase = true)
        val hasLeadingStar = pattern.startsWith('*')
        val hasTrailingStar = pattern.endsWith('*')
        val patternParts = pattern.split("*").filter { it.isNotEmpty() }
        if (patternParts.isEmpty()) return true
        // Anchor the first token at fileName start when the pattern has no leading '*'.
        if (!hasLeadingStar && !fileName.startsWith(patternParts.first(), ignoreCase = true)) return false
        // Anchor the last token at fileName end when the pattern has no trailing '*'.
        if (!hasTrailingStar && !fileName.endsWith(patternParts.last(), ignoreCase = true)) return false
        // Walk middle tokens in order, starting after any anchored prefix.
        var startIndex = if (!hasLeadingStar) patternParts.first().length else 0
        val afterFirst = if (!hasLeadingStar) patternParts.drop(1) else patternParts
        val middle = if (!hasTrailingStar && afterFirst.isNotEmpty()) afterFirst.dropLast(1) else afterFirst
        for (part in middle) {
            val index = fileName.indexOf(part, startIndex, ignoreCase = true)
            if (index < 0) return false
            startIndex = index + part.length
        }
        // If the trailing anchor consumed a token that overlaps startIndex, ensure no overlap.
        if (!hasTrailingStar && afterFirst.isNotEmpty()) {
            val lastTokenStart = fileName.length - afterFirst.last().length
            if (lastTokenStart < startIndex) return false
        }
        return true
    }

    fun findFiles(rootPath: Path, pattern: String, includeDirectories: Boolean = false): Stream<Path> {
        Timber.i("findFiles pattern=$pattern")
        if (!Files.exists(rootPath)) return emptyList<Path>().stream()
        return Files.list(rootPath).filter { path ->
            if (path.isDirectory() && !includeDirectories) {
                false
            } else {
                matchesGlob(path.name, pattern)
            }
        }
    }

    fun findFilesRecursive(
        rootPath: Path,
        pattern: String,
        maxDepth: Int = -1,
        includeDirectories: Boolean = false,
    ): Stream<Path> {
        Timber.i("findFilesRecursive pattern=$pattern depth=$maxDepth")
        if (!Files.exists(rootPath)) return emptyList<Path>().stream()

        val results = mutableListOf<Path>()
        walkThroughPath(rootPath, maxDepth) { path ->
            if (path.isDirectory()) {
                if (includeDirectories && matchesGlob(path.name, pattern)) {
                    results.add(path)
                }
            } else if (matchesGlob(path.name, pattern)) {
                results.add(path)
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
        // fast path: exact casing matches (common case, single stat vs N listFiles)
        val direct = File(baseDir, relativePath)
        if (direct.exists()) return direct
        return resolveCaseInsensitive(baseDir, relativePath).takeIf { it.exists() }
    }

    /**
     * Resolves a relative path against [baseDir] using case-insensitive matching
     * for each segment. Existing segments are matched against on-disk casing;
     * remaining (non-existent) segments are appended with their original casing.
     * Never returns null — safe for new files whose parent dirs may already exist
     * with different casing.
     */
    fun resolveCaseInsensitive(baseDir: File, relativePath: String): File {
        val segments = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        var current = baseDir
        for ((i, segment) in segments.withIndex()) {
            val match = current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) }
            if (match != null) {
                current = match
            } else {
                // append remaining segments verbatim
                for (j in i until segments.size) current = File(current, segments[j])
                return current
            }
        }
        return current
    }
}
