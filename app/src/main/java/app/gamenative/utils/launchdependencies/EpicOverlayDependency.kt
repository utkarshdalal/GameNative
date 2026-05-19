package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.service.epic.EpicService
import app.gamenative.utils.LOADING_PROGRESS_UNKNOWN
import com.winlator.container.Container
import timber.log.Timber

/**
 * Installs the EOS Overlay into the Wine prefix for Epic games.
 *
 * Matches Legendary's behavior of unconditionally provisioning the overlay — any
 * game that ships the EOS SDK will then read the HKCU\SOFTWARE\Epic Games\EOS\OverlayPath
 * registry value and LoadLibrary the overlay. Games without the SDK silently ignore it.
 *
 * This mirrors the pre-existing Proton / GOG-script-interpreter pattern in
 * [BionicDefaultProtonDependency] and [GogScriptInterpreterDependency].
 */
object EpicOverlayDependency : LaunchDependency {
    private const val TAG = "EpicOverlayDep"

    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean =
        gameSource == GameSource.EPIC

    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean =
        EpicService.isOverlayInstalled(container)

    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int): String =
        "Downloading EOS overlay"

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) {
        callbacks.setLoadingProgress(LOADING_PROGRESS_UNKNOWN)
        val result = EpicService.installOverlay(
            context = context,
            container = container,
            forceReinstall = false,
            onProgress = { done, total ->
                if (total > 0) {
                    callbacks.setLoadingProgress(done.toFloat() / total.toFloat())
                }
            },
        )
        if (result.isFailure) {
            // Non-fatal: games that use the SDK will still launch without the overlay,
            // they just won't show the in-game EOS HUD / notifications.
            Timber.tag(TAG).w(
                result.exceptionOrNull(),
                "EOS overlay install failed for gameId=%d — launching anyway",
                gameId,
            )
        }
    }
}
