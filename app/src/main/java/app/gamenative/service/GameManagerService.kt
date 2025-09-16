package app.gamenative.service

import android.content.Context
import android.net.Uri
import app.gamenative.data.DownloadInfo
import app.gamenative.data.Game
import app.gamenative.data.GameSource
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.data.PostSyncInfo
import app.gamenative.data.SteamApp
import app.gamenative.service.Steam.SteamGameManager
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import timber.log.Timber

@Singleton
class GameManagerService @Inject constructor(
    private val steamGameManager: SteamGameManager,
    // Add new game sources here
) {
    companion object {
        private var instance: GameManagerService? = null
        private var gameManagers: Map<GameSource, GameManager> = mapOf()

        fun initialize(context: Context) {
            if (instance == null) {
                val serviceInstance = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    GameManagerServiceEntryPoint::class.java,
                ).gameManagerService()

                instance = serviceInstance

                // Set up default game managers using the real steamGameManager
                gameManagers = mapOf(
                    GameSource.STEAM to serviceInstance.steamGameManager,
                    // Add new game sources here
                )
            }
        }

        fun initializeForPreview(managers: Map<GameSource, GameManager>) {
            gameManagers = managers
        }

        fun getManagerForGameSource(gameSource: GameSource): GameManager {
            return gameManagers[gameSource] ?: throw IllegalArgumentException("No manager found for game source: $gameSource")
        }

        /**
         * Get the appropriate game manager for a given game
         */
        fun getManagerForGame(appId: String): GameManager {
            val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
            return getManagerForGameSource(gameSource)
        }

        fun getStoreUrl(appId: String): Uri {
            return getManagerForGame(appId).getStoreUrl(appId)
        }

        fun getDownloadInfo(appId: String): DownloadInfo? {
            return getManagerForGame(appId).getDownloadInfo(appId)
        }

        fun isGameInstalled(context: Context, appId: String): Boolean {
            return getManagerForGame(appId).isGameInstalled(context, appId)
        }

        suspend fun isUpdatePending(appId: String): Boolean {
            return getManagerForGame(appId).isUpdatePending(appId)
        }

        fun deleteGame(context: Context, appId: String): Boolean {
            return getManagerForGame(appId).deleteGame(context, appId).isSuccess
        }

        fun downloadGame(context: Context, appId: String): DownloadInfo? {
            return getManagerForGame(appId).downloadGame(context, appId).getOrNull()
        }

        fun hasPartialDownload(appId: String): Boolean {
            return getManagerForGame(appId).hasPartialDownload(appId)
        }

        suspend fun getGameDiskSize(context: Context, appId: String): String {
            return getManagerForGame(appId).getGameDiskSize(context, appId)
        }

        fun getWineStartCommand(
            context: Context,
            appId: String,
            container: Container,
            bootToContainer: Boolean,
            appLaunchInfo: LaunchInfo?,
            envVars: EnvVars,
            guestProgramLauncherComponent: GuestProgramLauncherComponent,
        ): String {
            if (bootToContainer) {
                return "winhandler.exe \"wfm.exe\""
            }

            val args = getManagerForGame(appId).getWineStartCommand(
                context,
                appId,
                container,
                bootToContainer,
                appLaunchInfo,
                envVars,
                guestProgramLauncherComponent,
            )

            // Always use winhandler.exe wrapper for proper windowing and display
            return "winhandler.exe $args"
        }

        /**
         * Launch a game with appropriate save sync based on appId
         */
        suspend fun launchGameWithSaveSync(
            context: Context,
            appId: String,
            parentScope: CoroutineScope,
            ignorePendingOperations: Boolean = false,
            preferredSave: Int? = null,
        ): PostSyncInfo {
            return getManagerForGame(appId).launchGameWithSaveSync(
                context = context,
                appId = appId,
                parentScope = parentScope,
                ignorePendingOperations = ignorePendingOperations,
                preferredSave = preferredSave,
            )
        }

        /**
         * Get the app directory path for a given app ID
         */
        fun getAppDirPath(appId: String): String {
            val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
            return getManagerForGameSource(gameSource).getAppDirPath(appId)
        }

        fun getDownloadSize(appId: String): String {
            return getManagerForGame(appId).getDownloadSize(appId)
        }

        fun isValidToDownload(appId: String): Boolean {
            return getManagerForGame(appId).isValidToDownload(appId)
        }

        fun getAppInfo(appId: String): SteamApp? {
            return getManagerForGame(appId).getAppInfo(appId)
        }

        fun getReleaseDate(appId: String): String {
            return getManagerForGame(appId).getReleaseDate(appId)
        }

        fun getHeroImage(appId: String): String {
            return getManagerForGame(appId).getHeroImage(appId)
        }

        fun getIconImage(libraryItem: LibraryItem): String {
            return getManagerForGame(libraryItem.appId).getIconImage(libraryItem)
        }

        fun getInstallInfoDialog(context: Context, appId: String): MessageDialogState {
            return getManagerForGame(appId).getInstallInfoDialog(context, appId)
        }

        fun runBeforeLaunch(context: Context, appId: String) {
            getManagerForGame(appId).runBeforeLaunch(context, appId)
        }

        /**
         * Provides a flow of all games from all sources combined
         */
        fun getAllGames(): Flow<List<Game>> {
            // Get all pre-wrapped game flows from each manager
            val gameFlows = gameManagers.map { (_, manager) ->
                manager.getAllGames()
            }.toTypedArray()

            return combine(*gameFlows) { gameArrays ->
                val games = mutableListOf<Game>()

                gameArrays.forEachIndexed { index, wrappedGames ->
                    // Only log when there's actually a meaningful change
                    if (wrappedGames.isNotEmpty()) {
                        val gameSource = gameManagers.keys.elementAt(index)
                        Timber.tag("GameManagerService").d("Collecting ${wrappedGames.size} games from $gameSource")
                    }

                    // Games are already wrapped, just add them directly
                    games.addAll(wrappedGames)
                }

                games
            }.distinctUntilChanged() // Prevent duplicate emissions
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GameManagerServiceEntryPoint {
    fun gameManagerService(): GameManagerService
}
