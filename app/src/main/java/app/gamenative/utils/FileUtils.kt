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
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import java.util.stream.Collectors
import java.util.stream.Stream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import timber.log.Timber

object FileUtils {

    private const val MAX_UFS_RELATIVE_PATH_LENGTH = 199

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

    private val ufsPatternCache = ConcurrentHashMap<String, Pattern>()

    private fun compileUfsPattern(pattern: String): Pattern {
        val regex = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '*' -> regex.append(".*")
                '?' -> regex.append('.')
                '[' -> {
                    var j = i + 1
                    if (j < pattern.length && (pattern[j] == '!' || pattern[j] == '^')) j++
                    if (j < pattern.length && pattern[j] == ']') j++
                    while (j < pattern.length && pattern[j] != ']') j++
                    if (j >= pattern.length) {
                        regex.append(Pattern.quote(c.toString()))
                    } else {
                        var k = i + 1
                        regex.append('[')
                        if (pattern[k] == '!' || pattern[k] == '^') {
                            regex.append('^')
                            k++
                        }
                        while (k < j) {
                            appendClassChar(regex, pattern[k])
                            if (k + 2 < j && pattern[k + 1] == '-') {
                                regex.append('-')
                                appendClassChar(regex, pattern[k + 2])
                                k += 3
                            } else {
                                k++
                            }
                        }
                        regex.append(']')
                        i = j
                    }
                }
                else -> regex.append(Pattern.quote(c.toString()))
            }
            i++
        }
        val flags = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or Pattern.DOTALL
        return try {
            Pattern.compile(regex.toString(), flags)
        } catch (e: PatternSyntaxException) {
            Pattern.compile(Pattern.quote(pattern), flags)
        }
    }

    private fun appendClassChar(regex: StringBuilder, c: Char) {
        if (!c.isLetterOrDigit()) regex.append('\\')
        regex.append(c)
    }

    fun matchesUfsPattern(fileName: String, pattern: String): Boolean {
        if (pattern == "*" || pattern == "*.*") return true
        return ufsPatternCache.getOrPut(pattern) { compileUfsPattern(pattern) }.matcher(fileName).matches()
    }

    fun findFiles(rootPath: Path, pattern: String): Stream<Path> =
        findFilesRecursive(rootPath, pattern, recursive = false)

    fun findFilesRecursive(
        rootPath: Path,
        pattern: String,
        recursive: Boolean,
    ): Stream<Path> {
        if (!Files.isDirectory(rootPath)) return Stream.empty()
        val results = mutableListOf<Path>()

        fun scan(dir: Path, relativeDir: String) {
            if (relativeDir.length > MAX_UFS_RELATIVE_PATH_LENGTH) return
            val entries = try {
                Files.list(dir).use { it.collect(Collectors.toList()) }
            } catch (e: Exception) {
                Timber.w(e, "Failed to list $dir")
                return
            }
            entries.sortBy { it.name }
            val subdirectories = mutableListOf<Path>()
            for (entry in entries) {
                val name = entry.name
                if (name == "." || name == "..") continue
                val relativePath = if (relativeDir.isEmpty()) name else "$relativeDir/$name"
                if (Files.isDirectory(entry)) {
                    if (recursive && !Files.isSymbolicLink(entry)) subdirectories.add(entry)
                    continue
                }
                if (name.equals("steam_autocloud.vdf", ignoreCase = true)) continue
                if (relativePath.length > MAX_UFS_RELATIVE_PATH_LENGTH) continue
                if (matchesUfsPattern(name, pattern)) results.add(entry)
            }
            for (subdirectory in subdirectories) {
                val name = subdirectory.name
                scan(subdirectory, if (relativeDir.isEmpty()) name else "$relativeDir/$name")
            }
        }

        scan(rootPath, "")
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
        for (segment in segments) {
            val match = current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) }
            current = match ?: File(current, segment)
        }
        return current
    }
}
