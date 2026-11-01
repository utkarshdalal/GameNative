package app.gamenative.display

import android.content.Context
import app.gamenative.service.SteamService
import app.gamenative.utils.AssetUtils
import com.winlator.core.TarCompressorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** Versioned gamescope payload (gamescope, XWayland, scaling clients), fetched on the first gamescope launch. */
object GamescopePayload {
    const val VERSION = "20261101.6"
    const val ARCHIVE = "gamescope-gamenative-$VERSION.tzst"
    private const val STAMP = ".payload.version"

    fun directory(filesDir: File): File = GamescopeSettings.gamescopeDir(filesDir)

    fun isReady(filesDir: File): Boolean {
        val dir = directory(filesDir)
        return File(dir, "bin").isDirectory &&
            runCatching { File(dir, STAMP).readText().trim() }.getOrNull() == VERSION
    }

    suspend fun ensure(context: Context, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        if (isReady(filesDir)) return@withContext directory(filesDir)
        val archive = File(filesDir, ARCHIVE)
        if (!archive.isFile || archive.length() == 0L) {
            archive.delete()
            // A payload shipped inside the APK wins over the download: it is the
            // build that was exercised with this app, and it needs no network.
            // Only when the asset is absent does the published archive get
            // fetched.
            if (copyFromAssets(context, archive)) {
                onProgress(1.0f)
            } else {
                try {
                    SteamService.fetchFileWithFallback(ARCHIVE, archive, context, onProgress)
                } catch (e: Exception) {
                    archive.delete()
                    throw e
                }
            }
        }
        install(archive, filesDir)
    }

    /** Whether this APK carries the payload, and if so, copy it out to [dest]. */
    private fun copyFromAssets(context: Context, dest: File): Boolean {
        val assets = context.assets
        val present = runCatching { assets.open(ARCHIVE).close() }.isSuccess
        if (!present) return false
        return runCatching {
            assets.open(ARCHIVE).use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        }
            .onFailure {
                Timber.w(it, "Failed to copy $ARCHIVE from assets")
                dest.delete()
            }
            .isSuccess
    }

    @Synchronized
    internal fun install(archive: File, filesDir: File): File {
        val target = directory(filesDir)
        if (isReady(filesDir)) return target
        val tempDir = File(target.parentFile, "${target.name}.tmp")
        AssetUtils.deleteTree(tempDir)
        tempDir.mkdirs()
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, tempDir)) {
            AssetUtils.deleteTree(tempDir)
            archive.delete()
            error("Failed to extract $ARCHIVE")
        }
        File(tempDir, STAMP).writeText(VERSION)
        AssetUtils.deleteTree(target)
        if (!tempDir.renameTo(target)) {
            AssetUtils.deleteTree(tempDir)
            error("Failed to promote $ARCHIVE")
        }
        archive.delete()
        Timber.i("Gamescope payload $VERSION ready at ${target.absolutePath}")
        return target
    }
}
