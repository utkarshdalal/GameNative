package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.service.gog.GOGManifestUtils
import app.gamenative.service.gog.GOGService
import com.winlator.container.Container
import timber.log.Timber
import java.io.File

object GogScriptInterpreterDependency : LaunchDependency {
    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean {
        if (gameSource != GameSource.GOG) return false
        val installPath = GOGService.getInstallPath(gameId.toString()) ?: return false
        return GOGManifestUtils.needsScriptInterpreter(File(installPath))
    }

    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean =
        isRedistInstalled(gameId.toString())

    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int): String =
        "Downloading GOG script interpreter"

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) {
        if (isRedistInstalled(gameId.toString())) return
        val downloadManager = GOGService.getInstance()?.gogDownloadManager
            ?: run {
                Timber.tag("GOG").w("GOG service not available for redist download")
                return
            }
        Timber.tag("GOG").d("Downloading script interpreter (ISI) for GOG game")
        val installPath = GOGService.getInstallPath(gameId.toString())
        if (installPath == null) {
            Timber.tag("GOG").w("No game install path for GOG redist, skipping script interpreter")
            return
        }
        val redistDir = File(installPath, "_CommonRedist")
        val result = downloadManager.downloadRedist(redistDir, listOf("ISI")) { callbacks.setLoadingProgress(it) }
        if (result.isFailure) {
            Timber.tag("GOG").w(result.exceptionOrNull(), "GOG redist download failed, continuing anyway")
        }
    }

    /** True if GOG redist (ISI/scriptinterpreter.exe) is present in the game's _CommonRedist. */
    private fun isRedistInstalled(gameId: String): Boolean {
        val installPath = GOGService.getInstallPath(gameId)
        if (installPath == null) return false
        val redistDir = File(installPath, "_CommonRedist")
        return File(redistDir, "ISI/scriptinterpreter.exe").exists()
    }
}
