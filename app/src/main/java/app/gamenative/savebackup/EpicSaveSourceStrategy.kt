package app.gamenative.savebackup

import android.content.Context
import app.gamenative.service.epic.EpicCloudSavesManager
import com.winlator.container.Container
import java.io.File
import java.nio.file.Paths
import timber.log.Timber

/**
 * [SaveSourceStrategy] for Epic games (Requirement 15.1).
 *
 * For cloud-enabled Epic games the save folder is known from `EpicGame.saveFolder`, resolved
 * through Epic's own path-variable substitution. This strategy resolves that **absolute** folder
 * and maps it onto the most-specific supported [SaveRoot] via [SaveRootMatcher] (Requirement 15.3),
 * so the model stores a `PathType` + relative subpath, never an absolute path (Requirement 1.4),
 * and every existing containment/symlink/round-trip guard applies.
 *
 * When the save folder cannot be determined, or resolves outside every supported root, the strategy
 * returns [AutoResolveResult.NoAutomaticResolution] so the flow defers to the container browser
 * (Requirement 15.4).
 *
 * The absolute-folder lookup is injected ([resolveSaveFolder]) so the mapping is unit-testable
 * without Epic's async credential/metadata flows; the production default delegates to
 * [EpicCloudSavesManager.resolveSaveFolderForBackup].
 */
class EpicSaveSourceStrategy(
    private val resolveSaveFolder: (Context, Int) -> File? = { ctx, gameId ->
        EpicCloudSavesManager.resolveSaveFolderForBackup(ctx, gameId)
    },
) : SaveSourceStrategy {

    override fun resolveAutomatic(
        context: Context,
        container: Container,
        gameId: Int,
        intent: ResolveIntent,
    ): AutoResolveResult {
        val folder = try {
            resolveSaveFolder(context, gameId)
        } catch (e: Exception) {
            Timber.w(e, "Epic save-folder resolution failed for gameId=$gameId")
            null
        } ?: return AutoResolveResult.NoAutomaticResolution

        val root = SaveRootMatcher.matchWithinContainer(
            container = container,
            appId = gameId,
            accountId = 0L,
            absoluteDir = Paths.get(folder.absolutePath),
        ) ?: run {
            Timber.w("Epic save folder outside supported roots: %s", folder.absolutePath)
            return AutoResolveResult.NoAutomaticResolution
        }

        return AutoResolveResult.Found(SaveLocation.of(root))
    }
}
