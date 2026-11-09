package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.api.ApiResult
import app.gamenative.api.CodeCacheApi
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.FexCodeMaps
import app.gamenative.utils.FexImages
import app.gamenative.utils.HardwareUtils
import com.winlator.container.Container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Puts the hosted FEX code caches for the images this game loads into the prefix's fex-emu/cache/
 * before launch, so FEX loads translated code instead of compiling it. Caches are hosted per image
 * code identity (FexImages.codeSha256), FEX build and SoC; the server names each one the way FEX looks for it on this device
 * (image name plus file id from the PE header), so a stale file after a game update is ignored.
 * The lookup is re-checked once per [RECHECK_MS]; failures never block the launch.
 */
object FexCodeCacheDependency : LaunchDependency {

    private const val TAG = "FexCodeCache"
    private const val CACHE_DIR = ".wine/drive_c/users/xuser/AppData/Local/fex-emu/cache"
    private const val STEAM_DIR = ".wine/drive_c/Program Files (x86)/Steam"
    private const val RECHECK_MS = 6L * 60 * 60 * 1000
    private const val MAX_IMAGES = 64
    private const val MAX_IMAGE_BYTES = 512L * 1024 * 1024
    private val STEAM_CLIENT_DLLS = listOf(
        "steamclient64.dll",
        "steamclient.dll",
        "steam_api64.dll",
        "steam_api.dll",
        "gameoverlayrenderer64.dll",
        "gameoverlayrenderer.dll",
    )

    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean =
        PrefManager.fexCodeCacheEnabled && "fexcore".equals(container.emulator, ignoreCase = true)

    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean {
        // A pack without the offline compiler cannot load caches; nothing to do for it.
        if (!FexCodeMaps.supportsCodeMaps(context, container)) return true
        val stamp = stampFile(container)
        return stamp.exists() && System.currentTimeMillis() - stamp.lastModified() < RECHECK_MS
    }

    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int): String =
        "Checking FEX code cache"

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) = withContext(Dispatchers.IO) {
        try {
            refresh(container, callbacks, gameSource, gameId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Hosted cache check failed")
        }
    }

    private suspend fun refresh(container: Container, callbacks: LaunchDependencyCallbacks, gameSource: GameSource, gameId: Int) {
        val soc = HardwareUtils.getSOCName()
        if (soc.isNullOrBlank()) {
            Timber.tag(TAG).i("No SoC name; skipping hosted caches")
            return
        }
        val fexVersion = container.fexCoreVersion
        val images = FexImages.describe(container.rootDir, imageFiles(container, gameSource, gameId))
        if (images.isEmpty()) {
            Timber.tag(TAG).i("No images to look up")
        } else {
            val hosted = when (val result = CodeCacheApi.lookup(fexVersion, soc, images)) {
                is ApiResult.Success -> result.data
                is ApiResult.HttpError -> {
                    Timber.tag(TAG).w("Cache lookup failed: HTTP ${result.code} ${result.message}")
                    return
                }
                is ApiResult.NetworkError -> {
                    Timber.tag(TAG).w("Cache lookup failed: ${result.exception.message}")
                    return
                }
            }.filter { cache ->
                // The name becomes a path in the prefix; anything but a plain file name is refused.
                val name = cache.fileName
                val plain = name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }
                if (!plain) Timber.tag(TAG).w("Ignoring cache with file name ${cache.fileName}")
                plain
            }
            val dir = File(container.rootDir, CACHE_DIR)
            if (!dir.isDirectory && !dir.mkdirs()) {
                Timber.tag(TAG).w("Cannot create ${dir.path}")
                return
            }
            // What the device holds, by sha256, so an older version can take a patch instead of a full file.
            // A file of another size with no patch on offer is replaced whole, so it is not hashed.
            val local = hosted.associateWith { cache ->
                val file = File(dir, cache.fileName)
                when {
                    !file.isFile -> null
                    file.length() == cache.size || cache.patchFrom != null -> FexImages.sha256(file)
                    else -> ""
                }
            }
            val missing = hosted.filter { cache -> local[cache] != cache.sha256 }
            Timber.tag(TAG).i("$fexVersion on $soc: ${images.size} images, ${hosted.size} hosted caches, ${missing.size} to download")
            missing.forEachIndexed { index, cache ->
                callbacks.setLoadingMessage("Downloading FEX code cache (${index + 1}/${missing.size})")
                callbacks.setLoadingProgress(if (missing.size > 1) index.toFloat() / missing.size else -1f)
                val have = local[cache]?.takeIf { it.isNotEmpty() }
                runCatching { CodeCacheApi.download(cache, File(dir, cache.fileName), have) }
                    .onSuccess { renamed ->
                        if (renamed) {
                            Timber.tag(TAG).i("Updated ${cache.fileName} (${cache.size} B${if (have != null && have == cache.patchFrom) ", patched" else ""})")
                        } else {
                            Timber.tag(TAG).w("Could not move ${cache.fileName} into place")
                        }
                    }
                    .onFailure { Timber.tag(TAG).w(it, "Download of ${cache.fileName} failed") }
            }
        }
        stampFile(container).apply {
            parentFile?.mkdirs()
            writeText(fexVersion)
        }
    }

    /**
     * The images to look up: the game's executable, every .dll next to it and, for Steam games, the
     * Steam client DLLs in the prefix. At most [MAX_IMAGES], none over [MAX_IMAGE_BYTES].
     */
    private fun imageFiles(container: Container, gameSource: GameSource, gameId: Int): List<File> {
        val files = LinkedHashSet<File>()
        val exe = runCatching { executableFile(container, gameSource, gameId) }
            .onFailure { Timber.tag(TAG).w(it, "Cannot resolve the executable") }
            .getOrNull()
        if (exe != null && exe.isFile) {
            files.add(exe)
            exe.parentFile?.listFiles { f -> f.isFile && f.name.endsWith(".dll", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase() }
                ?.let { files.addAll(it) }
        } else {
            Timber.tag(TAG).i("Executable ${container.executablePath} not found on disk (${exe?.path})")
        }
        if (gameSource == GameSource.STEAM) {
            val steamDir = File(container.rootDir, STEAM_DIR)
            // The prefix keeps Steam's own casing (GameOverlayRenderer.dll) on a case-sensitive filesystem.
            steamDir.listFiles { f -> f.isFile && f.name.lowercase() in STEAM_CLIENT_DLLS }
                ?.sortedBy { it.name.lowercase() }
                ?.let { files.addAll(it) }
        }
        return files.filter { it.length() <= MAX_IMAGE_BYTES }.take(MAX_IMAGES)
    }

    /**
     * The container's executable as a host path, resolved the way the launcher does: an absolute
     * Windows path through the prefix's drive symlinks, a relative one against the Steam app
     * directory for Steam games and the A: drive (the game folder) for every other store.
     */
    private fun executableFile(container: Container, gameSource: GameSource, gameId: Int): File? {
        val path = container.executablePath.ifEmpty {
            if (gameSource == GameSource.STEAM) SteamService.getInstalledExe(gameId) else ""
        }
        if (path.isEmpty()) return null
        if (ContainerUtils.isAbsoluteWindowsPath(path)) {
            val letter = path[0].lowercaseChar()
            val rest = path.substring(3).replace('\\', '/')
            val drive = File(container.rootDir, ".wine/dosdevices/$letter:").takeIf { it.exists() }
                ?: Container.drivesIterator(container.drives).firstOrNull { it[0].equals(letter.toString(), ignoreCase = true) }?.let { File(it[1]) }
                ?: return null
            return File(drive, rest)
        }
        // Epic may keep the executable as a host path inside its install directory.
        if (path.startsWith("/") && File(path).isFile) return File(path)
        val base = if (gameSource == GameSource.STEAM) {
            SteamService.getAppDirPath(gameId)
        } else {
            ContainerUtils.getADrivePath(container.drives) ?: return null
        }
        return File(base, path.replace('\\', '/').trimStart('/'))
    }

    private fun stampFile(container: Container) = File(container.rootDir, ".gamenative/fex-cache-check-${container.fexCoreVersion}")
}
