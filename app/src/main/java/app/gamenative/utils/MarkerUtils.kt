package app.gamenative.utils

import app.gamenative.enums.Marker
import timber.log.Timber
import java.io.File

object MarkerUtils {
    private const val DOWNLOAD_INFO_DIR = ".DownloadInfo"
    private const val BYTES_DOWNLOADED_FILE = "bytes_downloaded.txt"
    private val VERIFY_PREREQUISITE_MARKERS = listOf(
        Marker.VCREDIST_INSTALLED,
        Marker.GOG_SCRIPT_INSTALLED,
        Marker.PHYSX_INSTALLED,
        Marker.OPENAL_INSTALLED,
        Marker.XNA_INSTALLED,
        Marker.UBISOFT_CONNECT_INSTALLED,
    )

    fun hasMarker(dirPath: String, type: Marker): Boolean {
        return File(dirPath, type.fileName).exists()
    }

    fun hasPartialInstall(dirPath: String): Boolean {
        if (dirPath.isBlank()) return false
        val dir = File(dirPath)
        return dir.exists() && !hasMarker(dirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
    }

    fun hasPersistedPartialProgress(dirPath: String): Boolean {
        if (dirPath.isBlank()) return false
        val bytesFile = File(File(dirPath, DOWNLOAD_INFO_DIR), BYTES_DOWNLOADED_FILE)
        return bytesFile.isFile && bytesFile.length() > 0L
    }

    fun hasResumablePartialInstall(dirPath: String): Boolean {
        if (dirPath.isBlank()) return false
        val dir = File(dirPath)
        if (!dir.isDirectory) return false
        if (hasMarker(dirPath, Marker.DOWNLOAD_COMPLETE_MARKER)) return false
        return hasMarker(dirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER) || hasPersistedPartialProgress(dirPath)
    }

    fun findResumablePartialInstalls(rootPath: String): Set<String> {
        val root = File(rootPath)
        if (!root.isDirectory) return emptySet()

        return root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && hasResumablePartialInstall(it.absolutePath) }
            ?.map { it.absolutePath }
            ?.toSet()
            .orEmpty()
    }

    fun addMarker(dirPath: String, type: Marker): Boolean {
        val dir = File(dirPath)
        if (File(dir, type.fileName).exists()) {
            Timber.i("Marker ${type.fileName} at $dirPath already exists")
            return true
        }
        if (dir.exists()) {
            try {
                File(dir, type.fileName).createNewFile()
                Timber.i("Added marker ${type.fileName} at $dirPath")
                return true
            } catch(e: Exception) {
                Timber.e(e, "Failed to add marker ${type.fileName} at $dirPath")
                return false
            }
        }
        Timber.e("Marker ${type.fileName} at $dirPath not added as directory not found")
        return false
    }

    fun removeMarker(dirPath: String, type: Marker): Boolean {
        val marker = File(dirPath, type.fileName)
        if (marker.exists()) {
            return marker.delete()
        }
        // Nothing to delete
        return true
    }

    /**
     * Clears marker files that represent completed prerequisite installs.
     * This is used by "Verify Files" flows so prerequisites can run again.
     */
    fun clearInstalledPrerequisiteMarkers(dirPath: String) {
        VERIFY_PREREQUISITE_MARKERS.forEach { removeMarker(dirPath, it) }
    }
}
