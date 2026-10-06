package app.gamenative.ui.screen.support

import android.content.Context
import app.gamenative.api.SupportFilesRequest
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
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

    private fun inside(root: File, file: File): Boolean {
        val rootPath = root.canonicalPath
        return file.canonicalPath.startsWith(rootPath + File.separator)
    }

    fun resolve(root: File, path: String): File? {
        if (!SupportFilesRequest.isSafePath(path)) return null
        val segments = path.replace('\\', '/').split('/')
        if (segments.first().equals(PATCH_DIR, ignoreCase = true)) return null
        var current = root
        for (segment in segments) {
            val exact = File(current, segment)
            current = if (exact.exists()) {
                exact
            } else {
                current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: exact
            }
        }
        return current.takeIf { inside(root, it) }
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
