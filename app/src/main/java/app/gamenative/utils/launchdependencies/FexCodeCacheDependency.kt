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
import app.gamenative.utils.LaunchSkip
import com.winlator.container.Container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Puts the hosted FEX code caches for the images this game loads into the prefix's fex-emu/cache/
 * before launch, so FEX loads translated code instead of compiling it. Caches are hosted per image
 * code identity (FexImages.codeSha256), FEX build and SoC; the server names each one the way FEX looks for it on this device
 * (image name plus file id from the PE header), so a stale file after a game update is ignored.
 * The lookup runs on every launch but holds it for at most [LOOKUP_TIMEOUT_MS], and downloads, which
 * the player can skip, for at most [DOWNLOAD_TIMEOUT_MS]; failures never block the launch.
 */
object FexCodeCacheDependency : LaunchDependency {

    private const val TAG = "FexCodeCache"
    private const val CACHE_DIR = ".wine/drive_c/users/xuser/AppData/Local/fex-emu/cache"
    private const val STEAM_DIR = ".wine/drive_c/Program Files (x86)/Steam"
    private const val INSTALLED_INDEX = ".gamenative/fex-cache-installed.json"
    private const val LOOKUP_TIMEOUT_MS = 3_000L
    private const val DOWNLOAD_TIMEOUT_MS = 3 * 60 * 1000L
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

    // A lookup that outlives the launch's wait keeps running here: it may be refreshing the account
    // token, and cancelling that mid-flight can lose the rotated refresh token.
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean =
        PrefManager.fexCodeCacheEnabled && "fexcore".equals(container.emulator, ignoreCase = true)

    // A pack without the offline compiler cannot load caches; nothing to do for it.
    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean =
        !FexCodeMaps.supportsCodeMaps(context, container)

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
            ensureActive()
            Timber.tag(TAG).w(e, "Hosted cache check was cancelled")
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
            return
        }
        val lookup = lookupScope.async { CodeCacheApi.lookup(fexVersion, soc, images) }
        val result = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { lookup.await() }
        if (result == null) {
            Timber.tag(TAG).w("Cache lookup took over $LOOKUP_TIMEOUT_MS ms; launching with the local caches")
            return
        }
        val hosted = when (result) {
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
        val indexFile = File(container.rootDir, INSTALLED_INDEX)
        val installed = runCatching { JSONObject(indexFile.readText()) }.getOrElse { JSONObject() }
        val indexBefore = installed.toString()
        try {
            // What the device holds, by sha256, so an older version can take a patch instead of a full file.
            // A file of another size with no patch on offer is replaced whole, so it is not hashed.
            val local = hosted.associateWith { cache ->
                val file = File(dir, cache.fileName)
                when {
                    !file.isFile -> null
                    file.length() == cache.size || cache.patchFrom != null -> installedSha256(file, installed)
                    else -> ""
                }
            }
            val missing = hosted.filter { cache -> local[cache] != cache.sha256 }
            Timber.tag(TAG).i("$fexVersion on $soc: ${images.size} images, ${hosted.size} hosted caches, ${missing.size} to download")
            if (missing.isNotEmpty()) downloadAll(missing, local, dir, installed, callbacks)
        } finally {
            saveIndex(indexFile, installed, indexBefore, dir)
        }
    }

    /**
     * Downloads [missing] one after another, as one phase the player can skip and that ends after
     * [DOWNLOAD_TIMEOUT_MS]. A skip or the time limit ends the phase without failing the launch.
     */
    private suspend fun downloadAll(
        missing: List<CodeCacheApi.HostedCache>,
        local: Map<CodeCacheApi.HostedCache, String?>,
        dir: File,
        installed: JSONObject,
        callbacks: LaunchDependencyCallbacks,
    ) {
        coroutineScope {
            val downloads = launch {
                val finished = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                    missing.forEachIndexed { index, cache ->
                        callbacks.setLoadingMessage("Downloading FEX code cache (${index + 1}/${missing.size})")
                        callbacks.setLoadingProgress(index.toFloat() / missing.size)
                        val have = local[cache]?.takeIf { it.isNotEmpty() }
                        val target = File(dir, cache.fileName)
                        try {
                            val renamed = CodeCacheApi.download(cache, target, have) { fraction ->
                                callbacks.setLoadingProgress((index + fraction) / missing.size)
                            }
                            if (renamed) {
                                record(installed, target, cache.sha256)
                                Timber.tag(TAG).i("Updated ${cache.fileName} (${cache.size} B${if (have != null && have == cache.patchFrom) ", patched" else ""})")
                            } else {
                                Timber.tag(TAG).w("Could not move ${cache.fileName} into place")
                            }
                        } catch (e: Exception) {
                            // A cancelled call can surface as an IOException; a skip or timeout must still end the phase.
                            ensureActive()
                            Timber.tag(TAG).w(e, "Download of ${cache.fileName} failed")
                        }
                    }
                }
                if (finished == null) Timber.tag(TAG).w("Downloads took over $DOWNLOAD_TIMEOUT_MS ms; launching with the caches in place")
            }
            LaunchSkip.offer { downloads.cancel() }
            try {
                downloads.join()
            } finally {
                LaunchSkip.withdraw()
            }
            if (downloads.isCancelled) Timber.tag(TAG).i("Downloads skipped")
        }
    }

    /** The sha256 of an installed cache, from [installed] while the file's size and mtime match it. */
    private fun installedSha256(file: File, installed: JSONObject): String? {
        val entry = installed.optJSONObject(file.name)?.takeIf {
            it.optLong("size", -1) == file.length() && it.optLong("mtime", -1) == file.lastModified() &&
                it.optString("sha256").length == 64
        }
        if (entry != null) return entry.getString("sha256")
        val sha256 = runCatching { FexImages.sha256(file) }.getOrElse {
            Timber.tag(TAG).w(it, "Cannot hash ${file.path}")
            return null
        }
        record(installed, file, sha256)
        return sha256
    }

    private fun record(installed: JSONObject, file: File, sha256: String) {
        installed.put(file.name, JSONObject().put("size", file.length()).put("mtime", file.lastModified()).put("sha256", sha256))
    }

    /** Writes [installed] when it changed, keeping the entries of caches that are still in [dir]. */
    private fun saveIndex(indexFile: File, installed: JSONObject, before: String, dir: File) {
        runCatching {
            installed.keys().asSequence().filter { !File(dir, it).isFile }.toList().forEach { installed.remove(it) }
            if (installed.toString() != before) {
                indexFile.parentFile?.mkdirs()
                indexFile.writeText(installed.toString())
            }
        }.onFailure { Timber.tag(TAG).w(it, "Cannot write ${indexFile.path}") }
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
}
