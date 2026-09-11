package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.service.rockstar.RockstarHelperArchive
import com.winlator.container.Container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** No download or extraction during ordinary launches. Trigger from installed Rockstar files. */
object RockstarHelperDependency : LaunchDependency {
    private fun gameDirectory(container: Container, gameSource: GameSource, gameId: Int): File? {
        if (gameSource == GameSource.STEAM) {
            val path = SteamService.getAppDirPath(gameId)
            if (path.isNotBlank()) return File(path)
        }
        // A: is the selected game directory for non-Steam sources as well.
        for (drive in Container.drivesIterator(container.drives)) {
            if (drive[0].equals("A", ignoreCase = true)) return File(drive[1])
        }
        return null
    }

    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean =
        gameSource == GameSource.STEAM && (container.isLaunchRealSteam || container.isLaunchBionicSteam) &&
            gameDirectory(container, gameSource, gameId)?.let(RockstarHelperArchive::usesRockstar) == true

    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean =
        RockstarHelperArchive.isReady(context.filesDir)

    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int): String =
        "Preparing Rockstar launcher support"

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) {
        withContext(Dispatchers.IO) {
            RockstarHelperArchive.ensureExtracted(context.filesDir) { context.assets.open(RockstarHelperArchive.ASSET) }
        }
        callbacks.setLoadingProgress(1f)
    }
}
