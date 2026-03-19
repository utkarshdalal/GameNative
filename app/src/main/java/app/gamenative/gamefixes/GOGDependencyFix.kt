package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.service.gog.GOGConstants
import app.gamenative.service.gog.GOGService
import com.winlator.container.Container
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class GOGDependencyFix(
    override val gameSource: GameSource,
    override val gameId: String,
    private val dependencyIds: List<String>,
) : KeyedGameFix {
    private fun isSatisfied(installPath: String): Boolean {
        if (dependencyIds.isEmpty()) return true
        val commonRedist = File(installPath, "_CommonRedist")
        val pathMap = GOGConstants.GOG_DEPENDENCY_INSTALLED_PATH
        return dependencyIds.all { id -> pathMap[id]?.let { File(commonRedist, it).exists() } == true }
    }

    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        if (isSatisfied(installPath)) return true
        val downloadManager = GOGService.getInstance()?.gogDownloadManager ?: return false
        val commonRedist = File(installPath, "_CommonRedist")
        return runBlocking(Dispatchers.IO) {
            downloadManager.downloadDependenciesWithProgress(
                gameId = gameId,
                dependencies = dependencyIds,
                gameDir = File(installPath),
                supportDir = commonRedist,
            ).isSuccess
        }
    }
}
