package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.utils.BestConfigService
import app.gamenative.utils.ManifestInstaller
import com.winlator.container.Container
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import timber.log.Timber

/** Steam manifest components (wine/proton, dxvk, etc.) missing from container config. */
internal object SteamManifestDependency : LaunchDependency {
    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int) =
        gameSource == GameSource.STEAM

    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int) =
        false // Resolved in install(); skip would require suspend resolveMissingManifestInstallRequests

    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int) =
        context.getString(R.string.main_downloading_entry, "manifest components")

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) {
        val configJson = Json.parseToJsonElement(container.containerJson).jsonObject
        val missingRequests = BestConfigService.resolveMissingManifestInstallRequests(
            context, configJson, "exact_gpu_match",
        )
        for (request in missingRequests) {
            callbacks.setLoadingMessage(context.getString(R.string.main_downloading_entry, request.entry.name))
            try {
                ManifestInstaller.installManifestEntry(
                    context, request.entry, request.isDriver, request.contentType,
                ) { progress -> callbacks.setLoadingProgress(progress.coerceIn(0f, 1f)) }
            } catch (e: Exception) {
                Timber.e(e, "Failed to install ${request.entry.name}, continuing")
            }
        }
    }
}
