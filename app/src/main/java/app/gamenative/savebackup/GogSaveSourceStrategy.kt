package app.gamenative.savebackup

import android.content.Context
import app.gamenative.service.gog.GOGService
import com.winlator.container.Container
import java.nio.file.Paths
import timber.log.Timber

/**
 * [SaveSourceStrategy] for GOG games (Requirement 15.2).
 *
 * For cloud-enabled GOG games the save location(s) are known from the GOG save-sync metadata
 * (`GOGManager.getSaveDirectoryPath`), which resolves one or more **absolute** folders. GOG games
 * can declare several locations, so this maps each to the most-specific supported [SaveRoot] via
 * [SaveRootMatcher] (Requirement 15.3) and returns them as a multi-root [SaveLocation]. The model
 * stores a `PathType` + relative subpath, never an absolute path (Requirement 1.4).
 *
 * When no save location can be determined, or all resolve outside the supported roots, the strategy
 * returns [AutoResolveResult.NoAutomaticResolution] so the flow defers to the container browser
 * (Requirement 15.4).
 *
 * The absolute-folder lookup is injected ([resolveSaveFolders]) so the mapping is unit-testable
 * without GOG's async API; the production default delegates to [GOGManager.getSaveDirectoryPath].
 */
class GogSaveSourceStrategy(
    private val resolveSaveFolders: (Context, String, String) -> List<String> = { ctx, appId, _ ->
        GOGService.getSaveDirectoryPathsForBackup(ctx, appId)
    },
) : SaveSourceStrategy {

    override fun resolveAutomatic(
        context: Context,
        container: Container,
        gameId: Int,
        intent: ResolveIntent,
    ): AutoResolveResult {
        // GOG keys its API by the source-prefixed appId (e.g. GOG_<id>).
        val appId = "GOG_$gameId"
        val folders = try {
            resolveSaveFolders(context, appId, "")
        } catch (e: Exception) {
            Timber.w(e, "GOG save-folder resolution failed for appId=$appId")
            emptyList()
        }
        if (folders.isEmpty()) return AutoResolveResult.NoAutomaticResolution

        val candidateRoots = SaveRootMatcher.candidateRoots(
            container = container,
            appId = gameId,
            includeSteamUserData = false,
        )

        val roots = folders.mapNotNull { folder ->
            SaveRootMatcher.match(candidateRoots, Paths.get(folder)).also { mapped ->
                if (mapped == null) {
                    Timber.w("GOG save folder outside supported roots: %s", folder)
                }
            }
        }.distinct()

        return if (roots.isNotEmpty()) {
            AutoResolveResult.Found(SaveLocation.of(roots))
        } else {
            AutoResolveResult.NoAutomaticResolution
        }
    }
}
