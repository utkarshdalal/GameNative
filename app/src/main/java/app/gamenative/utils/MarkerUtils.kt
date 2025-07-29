package app.gamenative.utils

import app.gamenative.enums.Marker
import timber.log.Timber
import java.io.File

object MarkerUtils {
    fun hasMarker(dirPath: String, type: Marker): Boolean {
        val file = File(dirPath, type.fileName)
        Timber.i("$file marker is ${file.exists()}")
        return File(dirPath, type.fileName).exists()
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
}
