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
        fun getManagerForGame(game: LibraryItem): GameManager {
            return getManagerForGameSource(game.gameSource)
        }

        fun getStoreUrl(libraryItem: LibraryItem): Uri {
            return getManagerForGame(libraryItem).getStoreUrl(libraryItem)
        }

        fun getDownloadInfo(libraryItem: LibraryItem): DownloadInfo? {
            return getManagerForGame(libraryItem).getDownloadInfo(libraryItem)
        }

        fun isGameInstalled(context: Context, libraryItem: LibraryItem): Boolean {
            return getManagerForGame(libraryItem).isGameInstalled(context, libraryItem)
        }

        suspend fun isUpdatePending(libraryItem: LibraryItem): Boolean {
            return getManagerForGame(libraryItem).isUpdatePending(libraryItem)
        }

        fun deleteGame(context: Context, libraryItem: LibraryItem): Boolean {
            return getManagerForGame(libraryItem).deleteGame(context, libraryItem).isSuccess
        }

        fun downloadGame(context: Context, libraryItem: LibraryItem): DownloadInfo? {
            return getManagerForGame(libraryItem).downloadGame(context, libraryItem).getOrNull()
        }

        fun hasPartialDownload(libraryItem: LibraryItem): Boolean {
            return getManagerForGame(libraryItem).hasPartialDownload(libraryItem)
        }

        suspend fun getGameDiskSize(context: Context, libraryItem: LibraryItem): String {
            return getManagerForGame(libraryItem).getGameDiskSize(context, libraryItem)
        }

        fun getWineStartCommand(
            context: Context,
            libraryItem: LibraryItem,
            container: Container,
            bootToContainer: Boolean,
            appLaunchInfo: LaunchInfo?,
            envVars: EnvVars,
            guestProgramLauncherComponent: GuestProgramLauncherComponent,
        ): String {
            if (bootToContainer) {
                return "winhandler.exe \"wfm.exe\""
            }

            val args = getManagerForGame(libraryItem).getWineStartCommand(
                context,
                libraryItem,
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
         * Launch a game with appropriate save sync based on LibraryItem
         */
        suspend fun launchGameWithSaveSync(
            context: Context,
            libraryItem: LibraryItem,
            parentScope: CoroutineScope,
            ignorePendingOperations: Boolean = false,
            preferredSave: Int? = null,
        ): PostSyncInfo {
            return getManagerForGame(libraryItem).launchGameWithSaveSync(
                context = context,
                libraryItem = libraryItem,
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

        /**
         * Helper function to create a LibraryItem from an appId string
         * This is a temporary solution until we have proper LibraryItem objects throughout the codebase
         */
        fun createLibraryItemFromAppId(appId: String, context: Context): LibraryItem {
            val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
            val gameId = ContainerUtils.extractGameIdFromContainerId(appId)

            return getManagerForGameSource(gameSource).createLibraryItem(appId, gameId.toString(), context)
        }

        fun getDownloadSize(libraryItem: LibraryItem): String {
            return getManagerForGame(libraryItem).getDownloadSize(libraryItem)
        }

        fun isValidToDownload(libraryItem: LibraryItem): Boolean {
            return getManagerForGame(libraryItem).isValidToDownload(libraryItem)
        }

        fun getAppInfo(libraryItem: LibraryItem): SteamApp? {
            return getManagerForGame(libraryItem).getAppInfo(libraryItem)
        }

        fun getReleaseDate(libraryItem: LibraryItem): String {
            return getManagerForGame(libraryItem).getReleaseDate(libraryItem)
        }

        fun getHeroImage(libraryItem: LibraryItem): String {
            return getManagerForGame(libraryItem).getHeroImage(libraryItem)
        }

        fun getInstallInfoDialog(context: Context, libraryItem: LibraryItem): MessageDialogState {
            return getManagerForGame(libraryItem).getInstallInfoDialog(context, libraryItem)
        }

        fun runBeforeLaunch(context: Context, libraryItem: LibraryItem) {
            getManagerForGame(libraryItem).runBeforeLaunch(context, libraryItem)
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
