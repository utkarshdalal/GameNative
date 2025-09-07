package app.gamenative.service

import android.content.Context
import app.gamenative.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

object DownloadService {
    private var lastUpdateTime: Long = 0
    private var downloadDirectoryApps: MutableList<String>? = null
    var baseDataDirPath: String = ""
        private set(value) {
            field = value
        }
    var baseCacheDirPath: String = ""
        private set(value) {
            field = value
        }

    fun populateDownloadService(context: Context) {
        baseDataDirPath = context.dataDir.path
        baseCacheDirPath = context.cacheDir.path
    }

    fun getDownloadDirectoryApps (): MutableList<String> {
        // What apps have folders in the download area?
        // Isn't checking for "complete" marker - incomplete is accepted

        // Only update if cache is over N milliseconds old
        val time = System.currentTimeMillis()
        if (lastUpdateTime < (time - 5 * 1000) || lastUpdateTime > time) {
            lastUpdateTime = time

            // For now, grab parent directories from SteamService
            val subDir = getSubdirectories(SteamService.internalAppInstallPath)
            subDir += getSubdirectories(SteamService.externalAppInstallPath)

            downloadDirectoryApps = subDir
        }

        return downloadDirectoryApps ?: mutableListOf()
    }

    private fun getSubdirectories (path: String): MutableList<String> {
        // Names of immediate subdirectories
        val subDir = File(path).list() { dir, name -> File(dir, name).isDirectory}
        if (subDir == null) {
            return emptyList<String>().toMutableList()
        }
        return subDir.toMutableList()
    }

    fun getSizeFromStoreDisplay (appId: Int): String {
        // How big is the game? The store should know. Human readable.
        val depots = SteamService.getDownloadableDepots(appId)
        val installBytes = depots.values.sumOf { it.manifests["public"]?.size ?: 0L }
        return StorageUtils.formatBinarySize(installBytes)
    }

    suspend fun getSizeOnDiskDisplay (appId: Int, setResult: (String) -> Unit) {
        // Outputs "3.76GiB" etc to the result lambda without locking up the main thread
        withContext(Dispatchers.IO) {
            // Do it async
            if (SteamService.isAppInstalled(appId)) {
                val appSizeText = StorageUtils.formatBinarySize(
                    StorageUtils.getFolderSize(SteamService.getAppDirPath(appId))
                )

                Timber.d("Finding $appId size on disk $appSizeText")
                setResult(appSizeText)
            }
        }
    }
}
