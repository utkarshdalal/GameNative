package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

/**
 * Deletes folders relative to the container's `drive_c/` on every launch.
 *
 * Used for games that leave stale per-run state in ProgramData (or similar) that
 * trips up DRM / bootstrap on the next start.
 */
class DeleteFolderFix(
    private val driveCRelativePaths: List<String>,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        var allSucceeded = true
        for (relativePath in driveCRelativePaths) {
            val target = File(container.rootDir, ".wine/drive_c/$relativePath")
            if (!target.exists()) continue
            try {
                if (target.deleteRecursively()) {
                    Timber.tag("GameFixes").i("Deleted '${target.absolutePath}' for game $gameId")
                } else {
                    Timber.tag("GameFixes").w("Partial delete of '${target.absolutePath}' for game $gameId")
                    allSucceeded = false
                }
            } catch (e: Exception) {
                Timber.tag("GameFixes").e(e, "Failed deleting '${target.absolutePath}' for game $gameId")
                allSucceeded = false
            }
        }
        return allSucceeded
    }
}

class KeyedDeleteFolderFix(
    override val gameSource: GameSource,
    override val gameId: String,
    driveCRelativePaths: List<String>,
) : KeyedGameFix, GameFix by DeleteFolderFix(driveCRelativePaths)
