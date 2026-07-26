package app.gamenative.service

import android.content.Context
import android.os.Environment
import app.gamenative.PrefManager
import app.gamenative.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

object DownloadService {
    @Volatile private var lastUpdateTime: Long = 0
    @Volatile private var downloadDirectoryApps: MutableList<String>? = null
    var baseDataDirPath: String = ""
        private set(value) {
            field = value
        }
    var baseCacheDirPath: String = ""
        private set(value) {
            field = value
        }
    // Base path to the app-specific external storage directory (Android/data/<package>)
    var baseExternalAppDirPath: String = ""
        private set(value) {
            field = value
        }

    // all mounted non-primary external volumes (SD cards, USB), discovered at init
    var externalVolumePaths: List<String> = emptyList()
        private set

    fun populateDownloadService(context: Context) {
        baseDataDirPath = context.dataDir.path
        baseCacheDirPath = context.cacheDir.path
        // Prefer the parent of external files dir (Android/data/<package>) so we can create siblings of /files
        val extFiles = context.getExternalFilesDir(null)
        baseExternalAppDirPath = extFiles?.parentFile?.path ?: ""

        val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
        val appFilesDirs = StorageUtils.getAllExternalFilesDirs(context)
            .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
            .filter { sm?.getStorageVolume(it)?.isPrimary != true }
        // both layouts per volume: legacy Android/data (existing installs) + public root (new installs)
        externalVolumePaths = appFilesDirs
            .flatMap { dir -> listOfNotNull(dir.absolutePath, StorageUtils.publicInstallRoot(dir)?.absolutePath) }
            .distinct()

        migrateExternalStoragePath()
    }

    // Android/data paths pay a ~1000x FUSE metadata penalty (MediaProvider disables kernel
    // caching there); repoint the install pref at the public root so new installs avoid it
    private fun migrateExternalStoragePath() {
        val pref = PrefManager.externalStoragePath
        if (pref.isBlank()) return
        val public: File
        val legacy: File?
        if (pref.contains("/Android/data/")) {
            legacy = File(pref)
            public = StorageUtils.publicInstallRoot(legacy) ?: return
            if (!StorageUtils.ensureInstallRoot(public)) return
            Timber.i("Migrating external install root from $pref to ${public.absolutePath}")
            PrefManager.externalStoragePath = public.absolutePath
        } else {
            // pref already migrated; legacy content may still need moving
            public = File(pref)
            legacy = externalVolumePaths.map(::File).firstOrNull {
                it.absolutePath.contains("/Android/data/") &&
                    StorageUtils.publicInstallRoot(it)?.absolutePath == public.absolutePath
            }
            if (legacy == null || !StorageUtils.ensureInstallRoot(public)) return
        }
        moveExternalContent(legacy, public)
    }

    // rename(2) only — same-volume moves are instant metadata ops even for huge libraries;
    // on failure the game stays in place and the dual-root scan keeps finding it
    private fun moveExternalContent(legacyRoot: File, publicRoot: File) {
        for (name in listOf("Steam", "GOG", "Epic", "Amazon")) {
            val src = File(legacyRoot, name)
            val dst = File(publicRoot, name)
            if (!src.isDirectory) continue
            if (dst.exists()) {
                Timber.w("Not moving $src: $dst already exists")
                continue
            }
            if (src.renameTo(dst)) {
                Timber.i("Moved $src to $dst")
            } else {
                Timber.w("Could not move $src to $dst; leaving in place")
            }
        }
    }

    @Synchronized
    fun invalidateCache() {
        lastUpdateTime = 0
    }

    @Synchronized
    fun getDownloadDirectoryApps (): MutableList<String> {
        // What apps have folders in the download area?
        // Isn't checking for "complete" marker - incomplete is accepted

        // Only update if cache is over N milliseconds old
        val time = System.currentTimeMillis()
        if (lastUpdateTime < (time - 5 * 1000) || lastUpdateTime > time) {
            lastUpdateTime = time

            // scan all install paths, deduplicate across volumes
            val dirs = mutableSetOf<String>()
            for (installPath in SteamService.allInstallPaths) {
                dirs += getSubdirectories(installPath)
            }

            downloadDirectoryApps = dirs.toMutableList()
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

    fun getSizeFromStoreDisplay (appId: Int, branch: String = "public"): String {
        val depots = SteamService.getDownloadableDepots(appId)
        val installBytes = depots.values.sumOf { (it.manifests[branch] ?: it.manifests["public"])?.size ?: 0L }
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
