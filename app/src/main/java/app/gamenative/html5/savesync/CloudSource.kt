package app.gamenative.html5.savesync

import android.content.Context
import app.gamenative.data.SteamApp
import app.gamenative.service.epic.EpicAuthManager
import app.gamenative.service.epic.EpicCloudSavesManager
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import java.io.File
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import app.gamenative.data.GameSource

// per-cloud-store abstraction over wine-side save roots. Html5SaveSyncService asks the source
// where saves live in the wine prefix; SaveDirectoryResolver consumes the resulting roots and
// picks the one containing chromium *.indexeddb.leveldb/ subtree. ALL store-specific path
// arithmetic lives in the variant -- resolver stays source-agnostic.
sealed class CloudSource {

    abstract val isSupported: Boolean

    // returns ABSOLUTE wine-prefix paths (e.g. <containerRoot>/.wine/drive_c/users/xuser/...).
    // resolver scans each for *.indexeddb.leveldb/ to pick the right one.
    abstract suspend fun wineSaveRoots(): List<File>

    // existing UFS-pattern path resolution stays in SaveDirectoryResolver where the helper
    // (winePrefixPathForRoot) already lives. SteamUfs only carries the SteamApp + container so
    // resolver can run the original CLOUD_ENABLED branch unchanged. wineSaveRoots returns empty
    // because the resolver drives the UFS-pattern walk directly off steamApp + container.
    data class SteamUfs(
        val steamApp: SteamApp,
        val container: Container,
    ) : CloudSource() {
        override val isSupported: Boolean
            get() = steamApp.ufs.saveFilePatterns.isNotEmpty()

        override suspend fun wineSaveRoots(): List<File> = emptyList()
    }

    // GOG remote-config-driven source. queries GOGManager.getSaveDirectoryPath which returns
    // resolved abs paths under <containerRoot>/.wine/drive_c/... per GOGCloudSavesLocation.
    data class GogRemoteConfig(
        val context: Context,
        val appId: String,
    ) : CloudSource() {
        // first call seeds; subsequent calls reuse -- prevents duplicate remote-config fetch
        // when isSupported and wineSaveRoots() both fire in the same resolveSetup pass.
        @Volatile private var cachedRoots: List<File>? = null

        override val isSupported: Boolean
            get() {
                // synchronous gate runs the suspend lookup via runBlocking -- same shape
                // GOGService.getInstallPath uses for compile-side bridges (GOGManager.kt).
                // resolveSetup runs on Dispatchers.IO via withContext in syncInbound/syncOutbound.
                val roots = runBlocking { wineSaveRoots() }
                return roots.isNotEmpty()
            }

        override suspend fun wineSaveRoots(): List<File> {
            cachedRoots?.let { return it }
            val gameId = runCatching { ContainerUtils.extractGameIdFromContainerId(appId) }.getOrNull()
                ?: return emptyList<File>().also { cachedRoots = it }
            val mgr = GOGService.getInstance()?.gogManager
                ?: return emptyList<File>().also {
                    Timber.tag(TAG).d("GogRemoteConfig: GOGService not running for appId=%s", appId)
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

    // Epic save-folder-template source. mirrors GogRemoteConfig: queries
    // EpicCloudSavesManager.resolveSaveDirectory which expands EpicGame.saveFolder against
    // the html5 container's wine prefix ({localappdata}/{userdir}/{usersavedgames}/...).
    // resolver scans the returned root for chromium *.indexeddb.leveldb/ to bind WebView LS/IDB
    // to the same path Epic's wine-side syncCloudSaves walks on exit.
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
            val game = EpicService.getEpicGameOf(gameId)
                ?: return emptyList<File>().also {
                    Timber.tag(TAG).d("EpicSavedGames: no EpicGame for id=%d", gameId)
                    cachedRoots = it
                }
            // saveFolder must be present for cloud sync to be possible. Epic ships a template
            // string ({appdata}/Game/Saved/...) per title; absent = title doesn't support cloud.
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

    // greenworks programmatic-cloud source. Cookie Clicker class --
    // game calls greenworks.fileWrite(name, data) directly; bytes live in WebView LS
    // under gn:gw:* keys, NOT on the wine prefix. resolver short-circuits BEFORE
    // SaveDirectoryResolver.resolve for this variant; wineSaveRoots returns empty.
    // observed = WebViewContainer.greenworksCloudObserved snapshot at resolve time.
    data class GreenworksCloud(
        val appId: String,
        val container: Container,
        val observed: Boolean,
    ) : CloudSource() {
        // isSupported widened from "UFS-only" to "UFS ∪ greenworks". container
        // with observed=true bypasses the no-cloud snackbar path.
        override val isSupported: Boolean get() = observed

        // greenworks bytes don't live on the wine prefix -- they're enumerated from
        // WebView localStorage at the boundary via evaluateJavascript
        // and uploaded directly via GreenworksCloudClient. resolver's
        // SaveDirectoryResolver.resolve walk is bypassed entirely.
        override suspend fun wineSaveRoots(): List<File> = emptyList()
    }
}
