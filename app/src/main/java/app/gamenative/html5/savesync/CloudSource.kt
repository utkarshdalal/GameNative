package app.gamenative.html5.savesync

import android.content.Context
import app.gamenative.data.SteamApp
import app.gamenative.service.epic.EpicAuthManager
import app.gamenative.service.epic.EpicCloudSavesManager
import app.gamenative.service.epic.EpicManager
import app.gamenative.service.gog.GOGManager
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import app.gamenative.data.GameSource

// where each store keeps saves in the Wine prefix. ALL store-specific path arithmetic lives here so
// SaveDirectoryResolver stays store-agnostic.
sealed class CloudSource {

    // Hilt, NOT <Store>Service.getInstance(): android can stop a dataSync FGS right before the
    // exit-time lookup, which silently downgraded outbound sync to "no cloud config" and skipped it.
    // the @Singleton managers only need Room plus stored credentials, not a live service.
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface StoreManagerEntryPoint {
        fun gogManager(): GOGManager
        fun epicManager(): EpicManager
    }

    abstract val isSupported: Boolean

    // null only if the Hilt graph is unavailable (unit tests without a Hilt application).
    protected fun storeManagers(context: Context): StoreManagerEntryPoint? = runCatching {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            StoreManagerEntryPoint::class.java,
        )
    }.onFailure {
        Timber.tag("CloudSource").w(it, "store manager entry point unavailable")
    }.getOrNull()

    // ABSOLUTE Wine-prefix paths; the resolver picks the one holding *.indexeddb.leveldb/.
    abstract suspend fun wineSaveRoots(): List<File>

    // no roots: SaveDirectoryResolver walks the UFS patterns itself off steamApp + container.
    data class SteamUfs(
        val steamApp: SteamApp,
        val container: Container,
    ) : CloudSource() {
        override val isSupported: Boolean
            get() = steamApp.ufs.saveFilePatterns.isNotEmpty()

        override suspend fun wineSaveRoots(): List<File> = emptyList()
    }

    data class GogRemoteConfig(
        val context: Context,
        val appId: String,
    ) : CloudSource() {
        // isSupported and wineSaveRoots() both run in one resolveSetup pass; fetch remote config once.
        @Volatile private var cachedRoots: List<File>? = null

        override val isSupported: Boolean
            get() {
                // runBlocking is safe: resolveSetup only runs on Dispatchers.IO.
                val roots = runBlocking { wineSaveRoots() }
                return roots.isNotEmpty()
            }

        override suspend fun wineSaveRoots(): List<File> {
            cachedRoots?.let { return it }
            val gameId = runCatching { ContainerUtils.extractGameIdFromContainerId(appId) }.getOrNull()
                ?: return emptyList<File>().also { cachedRoots = it }
            val mgr = storeManagers(context)?.gogManager()
                ?: return emptyList<File>().also {
                    Timber.tag(TAG).w("GogRemoteConfig: GOGManager unavailable for appId=%s", appId)
                    cachedRoots = it
                }
            val game = mgr.getGameFromDbById(gameId.toString())
                ?: return emptyList<File>().also {
                    Timber.tag(TAG).d("GogRemoteConfig: no GOGGame row for gameId=%s", gameId)
                    cachedRoots = it
                }
            val locations = mgr.getSaveDirectoryPath(context, appId, game.title).orEmpty()
            val roots = locations.map { File(it.location) }
            Timber.tag(TAG).i(
                "GogRemoteConfig.wineSaveRoots: appId=%s title=%s roots=%d",
                appId, game.title, roots.size,
            )
            cachedRoots = roots
            return roots
        }

        companion object {
            private const val TAG = "CloudSource"
        }
    }

    // expands EpicGame.saveFolder against the container's prefix -- the same dir Epic's cloud sync walks.
    data class EpicSavedGames(
        val context: Context,
        val appId: String,
    ) : CloudSource() {
        @Volatile private var cachedRoots: List<File>? = null

        override val isSupported: Boolean
            get() {
                val roots = runBlocking { wineSaveRoots() }
                return roots.isNotEmpty()
            }

        override suspend fun wineSaveRoots(): List<File> {
            cachedRoots?.let { return it }
            val gameId = GameSource.EPIC.idOf(appId).toIntOrNull()
                ?: return emptyList<File>().also { cachedRoots = it }
            val game = storeManagers(context)?.epicManager()?.getGameById(gameId)
                ?: return emptyList<File>().also {
                    Timber.tag(TAG).d("EpicSavedGames: no EpicGame for id=%d", gameId)
                    cachedRoots = it
                }
            // empty saveFolder template = title has no Epic cloud saves.
            if (game.saveFolder.isEmpty()) {
                Timber.tag(TAG).i("EpicSavedGames: empty saveFolder for appId=%s — no cloud", appId)
                cachedRoots = emptyList()
                return cachedRoots!!
            }
            val creds = EpicAuthManager.getStoredCredentials(context).getOrNull()
                ?: return emptyList<File>().also {
                    Timber.tag(TAG).i("EpicSavedGames: no Epic credentials (offline / not authed)")
                    cachedRoots = it
                }
            val saveDir = EpicCloudSavesManager.resolveSaveDirectory(context, game, creds.accountId)
                ?: return emptyList<File>().also {
                    Timber.tag(TAG).i("EpicSavedGames: resolveSaveDirectory returned null appId=%s", appId)
                    cachedRoots = it
                }
            val roots = listOf(saveDir)
            Timber.tag(TAG).i(
                "EpicSavedGames.wineSaveRoots: appId=%s title=%s root=%s",
                appId, game.title, saveDir.absolutePath,
            )
            cachedRoots = roots
            return roots
        }

        companion object {
            private const val TAG = "CloudSource"
        }
    }

    // games that call greenworks.fileWrite directly: bytes live in WebView LS under gn:gw:* keys,
    // NOT in the Wine prefix, and are uploaded via GreenworksCloudClient. no resolver walk.
    // observed = WebViewContainer.greenworksCloudObserved at resolve time.
    data class GreenworksCloud(
        val appId: String,
        val container: Container,
        val observed: Boolean,
    ) : CloudSource() {
        override val isSupported: Boolean get() = observed

        override suspend fun wineSaveRoots(): List<File> = emptyList()
    }
}
