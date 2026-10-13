package app.gamenative.ui.screen.support

import android.content.Context
import app.gamenative.api.SupportFilesRequest
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.io.InterruptedIOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Job

object SupportGameFiles {

    const val PATCH_DIR = ".gamenative-patch"
    private const val BUFFER = 256 * 1024

    fun installRoot(context: Context, appId: String): File? {
        val path = when (ContainerUtils.extractGameSourceFromContainerId(appId)) {
            GameSource.STEAM -> SteamService.getAppDirPath(ContainerUtils.extractGameIdFromContainerId(appId))
            GameSource.CUSTOM_GAME -> CustomGameScanner.getFolderPathFromAppId(appId)
            else -> if (ContainerUtils.hasContainer(context, appId)) {
                ContainerUtils.getADrivePath(ContainerUtils.getContainer(context, appId).drives)
            } else {
                null
            }
        }
        return path?.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isDirectory }
    }

    enum class UserRoot(val token: String, val subPath: String, val inPrefix: Boolean = false) {
        APPDATA("%APPDATA%", "AppData/Roaming"),
        LOCALAPPDATA("%LOCALAPPDATA%", "AppData/Local"),
        USERPROFILE("%USERPROFILE%", ""),
        DOCUMENTS("%DOCUMENTS%", "Documents"),
        WINEPREFIX("%WINEPREFIX%", "", inPrefix = true),
        STEAM("%STEAM%", "drive_c/Program Files (x86)/Steam", inPrefix = true),
    }

    data class ListingEntry(val name: String, val size: Long, val dir: Boolean, val mtime: Long)

    class Listing(val entries: List<ListingEntry>, val truncated: Boolean)

    const val MAX_LISTING_ENTRIES = 2000
    private const val MAX_LISTING_NAME = 255

    data class Located(val root: UserRoot?, val rest: String)

    class Roots(val install: File, val userHome: File?, val prefix: File? = null)

    fun locate(path: String): Located {
        val normalized = path.replace('\\', '/')
        val slash = normalized.indexOf('/')
        if (slash > 0) {
            val head = normalized.substring(0, slash)
            val root = UserRoot.entries.firstOrNull { it.token.equals(head, ignoreCase = true) }
            if (root != null) return Located(root, normalized.substring(slash + 1))
        }
        return Located(null, normalized)
    }

    fun wineUserHome(prefix: File): File {
        val users = File(prefix, "drive_c/users")
        val preferred = File(users, ImageFs.USER)
        if (preferred.isDirectory) return preferred
        return users.listFiles()
            ?.firstOrNull { it.isDirectory && !it.name.equals("Public", ignoreCase = true) }
            ?: preferred
    }

    fun roots(context: Context, appId: String): Roots? {
        val install = installRoot(context, appId) ?: return null
        val prefix = runCatching {
            if (ContainerUtils.hasContainer(context, appId)) {
                File(ContainerUtils.getContainer(context, appId).rootDir, ".wine")
            } else {
                null
            }
        }.getOrNull()
        val userHome = prefix?.let { runCatching { wineUserHome(it) }.getOrNull() }
        return Roots(install, userHome, prefix)
    }

    fun registryHive(roots: Roots, hive: String): File? {
        val prefix = roots.prefix ?: return null
        val name = when (hive) {
            "HKCU" -> "user.reg"
            "HKLM" -> "system.reg"
            else -> return null
        }
        return File(prefix, name).takeIf { it.isFile }
    }

    private fun inside(root: File, file: File): Boolean {
        val rootPath = root.canonicalPath
        return file.canonicalPath.startsWith(rootPath + File.separator)
    }

    private fun walk(start: File, segments: List<String>): File {
        var current = start
        for (segment in segments) {
            val exact = File(current, segment)
            current = if (exact.exists()) {
                exact
            } else {
                current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: exact
            }
        }
        return current
    }

    fun resolve(roots: Roots, path: String): File? {
        if (!SupportFilesRequest.isSafePath(path)) return null
        val located = locate(path)
        val userRoot = located.root ?: return resolve(roots.install, path)
        if (located.rest.isEmpty()) return null
        val base = walk(
            (if (userRoot.inPrefix) roots.prefix else roots.userHome) ?: return null,
            userRoot.subPath.split('/').filter { it.isNotEmpty() },
        )
        if (userRoot == UserRoot.STEAM && !base.isDirectory) return null
        val target = walk(base, located.rest.split('/'))
        return target.takeIf { inside(base, it) }
    }

    fun resolveDir(roots: Roots, path: String): File? {
        val dir = if (path == SupportFilesRequest.INSTALL_ROOT) roots.install else resolve(roots, path)
        return dir?.takeIf { it.isDirectory }
    }

    fun list(dir: File, depth: Int, job: Job? = null): Listing {
        val entries = mutableListOf<ListingEntry>()
        var truncated = false
        var level = listOf(dir to "")
        for (step in 1..depth.coerceIn(1, SupportFilesRequest.MAX_DEPTH)) {
            val next = mutableListOf<Pair<File, String>>()
            for ((folder, prefix) in level) {
                val children = folder.listFiles()?.sortedBy { it.name.lowercase() } ?: continue
                for (child in children) {
                    if (job != null && !job.isActive) throw InterruptedIOException("cancelled")
                    val name = prefix + child.name
                    if (name.length > MAX_LISTING_NAME || name.any { it == '\\' || it.code < 0x20 || it.code == 0x7f }) continue
                    if (entries.size >= MAX_LISTING_ENTRIES) {
                        truncated = true
                        break
                    }
                    val isDir = child.isDirectory
                    entries += ListingEntry(name, if (isDir) 0L else child.length(), isDir, child.lastModified().coerceAtLeast(0L))
                    if (isDir && !Files.isSymbolicLink(child.toPath())) next += child to "$name/"
                }
                if (truncated) break
            }
            if (truncated) break
            level = next
        }
        return Listing(entries.sortedBy { it.name.lowercase() }, truncated)
    }

    private fun resolve(root: File, path: String): File? {
        if (!SupportFilesRequest.isSafePath(path)) return null
        val segments = path.replace('\\', '/').split('/')
        if (segments.first().equals(PATCH_DIR, ignoreCase = true)) return null
        return walk(root, segments).takeIf { inside(root, it) }
    }

    fun sha256(file: File, job: Job? = null, onProgress: ((Float) -> Unit)? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            var read = 0L
            while (true) {
                if (job != null && !job.isActive) throw InterruptedIOException("cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                read += count
                if (total > 0) onProgress?.invoke((read.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun replaceAtomically(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.gnpatch.tmp")
        try {
            source.copyTo(temp, overwrite = true)
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }
}
