package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.api.ApiResult
import app.gamenative.api.CodeCacheApi
import app.gamenative.data.GameSource
import app.gamenative.utils.FexCodeMaps
import app.gamenative.utils.HardwareUtils
import com.winlator.container.Container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * Puts the hosted FEX code caches for this game, FEX build and SoC into the prefix's
 * fex-emu/cache/ before launch, so FEX loads translated code instead of compiling it. FEX only
 * picks a cache whose name carries the loaded image's file id, so stale files after a game
 * update are ignored. The list is re-checked once per [RECHECK_MS]; failures never block the launch.
 */
object FexCodeCacheDependency : LaunchDependency {

    private const val TAG = "FexCodeCache"
    private const val CACHE_DIR = ".wine/drive_c/users/xuser/AppData/Local/fex-emu/cache"
    private const val RECHECK_MS = 6L * 60 * 60 * 1000

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
        val soc = HardwareUtils.getSOCName()
        if (soc.isNullOrBlank()) {
            Timber.tag(TAG).i("No SoC name; skipping hosted caches")
            return@withContext
        }
        val fexVersion = container.fexCoreVersion
        val listed = when (val result = CodeCacheApi.list(gameSource.name, gameId, fexVersion, soc)) {
            is ApiResult.Success -> result.data
            is ApiResult.HttpError -> {
                Timber.tag(TAG).w("Cache list failed: HTTP ${result.code} ${result.message}")
                return@withContext
            }
            is ApiResult.NetworkError -> {
                Timber.tag(TAG).w("Cache list failed: ${result.exception.message}")
                return@withContext
            }
        }
        val dir = File(container.rootDir, CACHE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Timber.tag(TAG).w("Cannot create ${dir.path}")
            return@withContext
        }
        // What the device holds, by sha256, so an older version can take a patch instead of a full file.
        val local = listed.associateWith { cache -> File(dir, cache.name).takeIf { it.isFile }?.let { sha256(it) } }
        val missing = listed.filter { cache -> local[cache] != cache.sha256 }
        Timber.tag(TAG).i("$fexVersion on $soc: ${listed.size} hosted caches, ${missing.size} to download")
        missing.forEachIndexed { index, cache ->
            callbacks.setLoadingMessage("Downloading FEX code cache (${index + 1}/${missing.size})")
            callbacks.setLoadingProgress(if (missing.size > 1) index.toFloat() / missing.size else -1f)
            val have = local[cache]
            runCatching { CodeCacheApi.download(cache, File(dir, cache.name), have) }
                .onSuccess { Timber.tag(TAG).i("Updated ${cache.name} (${cache.size} B${if (have == cache.patchFrom) ", patched" else ""})") }
                .onFailure { Timber.tag(TAG).w(it, "Download of ${cache.name} failed") }
        }
        stampFile(container).apply {
            parentFile?.mkdirs()
            writeText(fexVersion)
        }
    }

    private fun stampFile(container: Container) = File(container.rootDir, ".gamenative/fex-cache-check-${container.fexCoreVersion}")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
