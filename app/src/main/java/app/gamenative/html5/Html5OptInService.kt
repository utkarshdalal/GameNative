package app.gamenative.html5

import android.content.Context
import app.gamenative.R
import app.gamenative.html5.fingerprint.FingerprintResult
import app.gamenative.html5.fingerprint.fingerprint
import app.gamenative.html5.profile.ProfileRegistry
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import com.winlator.container.ContainerData
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber
import app.gamenative.data.GameSource

// takes ContainerData (not a Container) so the caller can still bail before writing. the
// WebViewContainer JSON is persisted BEFORE any Container mutation; if the caller then fails to
// flip the variant, the JSON is a harmless orphan.
object Html5OptInService {

    sealed interface Result {
        data object Matched : Result
        data class NoMatch(val message: String) : Result
        data class CannotResolveInstallPath(val message: String) : Result
        // fingerprint matched but the pack JSON is missing/unparseable -- our bug, surface loudly.
        data class PackLoadFailure(val engineId: String) : Result
    }

    // install path -> (dir mtime, matched). fingerprinting can unpack asar/zip probes, so it only re-runs when
    // the install dir changes.
    private val capabilityCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    // whether the container dialog offers the html5 variant. wine stays the default; nothing flips on its own.
    // caller must dispatch on Dispatchers.IO.
    fun isWebViewCapable(appId: String): Boolean {
        val root = resolveFingerprintPath(appId) ?: return false
        val mtime = root.lastModified()
        capabilityCache[root.absolutePath]?.let { (cachedMtime, matched) -> if (cachedMtime == mtime) return matched }
        val matched = runCatching { fingerprint(root) is FingerprintResult.Matched }
            .onFailure { Timber.tag(TAG).w(it, "fingerprint failed for $appId") }
            .getOrDefault(false)
        capabilityCache[root.absolutePath] = mtime to matched
        return matched
    }

    // no container = first install; an update of a game the user already runs under wine stays quiet.
    fun notifyIfSwitchable(context: Context, gameSource: GameSource, gameId: String) {
        if (gameSource == GameSource.AMAZON) return
        val appId = "${gameSource.name}_$gameId"
        if (ContainerUtils.hasContainer(context, appId) || !isWebViewCapable(appId)) return
        val name = resolveFingerprintPath(appId)?.name ?: return
        SnackbarManager.show(context.getString(R.string.html5_download_switchable, name))
    }

    // an update can swap the engine (e.g. an MV->MZ port), so launch re-fingerprints when the install dir
    // moved. a non-match never reverts: saves and config are tied to html5, a manual flip is safer.
    fun refreshEngineIfChanged(context: Context, slug: String, container: WebViewContainer): WebViewContainer {
        val root = File(container.installPath).takeIf { it.isDirectory } ?: return container
        val mtime = root.lastModified()
        if (container.fingerprintMtime == mtime) return container
        val match = runCatching { fingerprint(root) }
            .onFailure { Timber.tag(TAG).w(it, "re-fingerprint failed for ${container.id}") }
            .getOrNull() as? FingerprintResult.Matched
        if (match == null) {
            Timber.tag(TAG).w("${container.id} is html5 but no engine matches now -- leaving as-is")
        }
        val engineChanged = match != null && match.engine != container.engineProfile
        val refreshed = if (match == null) {
            container.copy(fingerprintMtime = mtime)
        } else {
            container.copy(
                engineProfile = match.engine,
                webRoot = if (engineChanged) match.webRoot else container.webRoot,
                fingerprintMtime = mtime,
                fingerprintedEngineId = match.engine,
                subEngine = if (engineChanged) match.subEngine else match.subEngine ?: container.subEngine,
            )
        }
        runCatching { WebViewContainer.save(slug, refreshed) }
            .onFailure { Timber.tag(TAG).e(it, "persist refreshed fingerprint failed for $slug") }
        if (engineChanged) {
            SnackbarManager.show(
                context.getString(R.string.html5_engine_changed, root.name, container.engineProfile, match!!.engine),
            )
            Timber.tag(TAG).i("${container.id} engine changed ${container.engineProfile} -> ${match.engine}")
        }
        return refreshed
    }

    fun resolveFingerprintPath(appId: String): File? {
        val path = when {
            GameSource.CUSTOM_GAME.matches(appId) ->
                CustomGameScanner.getFolderPathFromAppId(appId)
            GameSource.STEAM.matches(appId) ->
                GameSource.STEAM.idOf(appId).toIntOrNull()?.let { SteamService.getAppDirPath(it) }
            GameSource.GOG.matches(appId) ->
                GOGService.getInstallPath(GameSource.GOG.idOf(appId))
            GameSource.EPIC.matches(appId) ->
                GameSource.EPIC.idOf(appId).toIntOrNull()?.let { EpicService.getInstallPath(it) }
            GameSource.AMAZON.matches(appId) ->
                GameSource.AMAZON.idOf(appId).toIntOrNull()?.let { AmazonService.getInstallPathByAppId(it) }
            else -> null
        } ?: return null
        return File(path).takeIf { it.isDirectory }
    }

    // caller must dispatch on Dispatchers.IO.
    suspend fun optIn(
        context: Context,
        appId: String,
        containerData: ContainerData,
    ): Result {
        val root = resolveFingerprintPath(appId)
            ?: return Result.CannotResolveInstallPath(
                context.getString(R.string.html5_optin_install_path_unresolvable),
            )

        val match = when (val r = fingerprint(root)) {
            is FingerprintResult.Matched -> r
            // recognized but unpacked -- same user-facing result as no match.
            is FingerprintResult.Candidate -> return Result.NoMatch(
                context.getString(R.string.html5_optin_no_engine, root.absolutePath) +
                    " (looks like ${r.engineHint})",
            )
            FingerprintResult.Unknown -> return Result.NoMatch(
                context.getString(R.string.html5_optin_no_engine, root.absolutePath),
            )
        }

        val slug = Html5SlugUtil.slug(root.name, appId)
        val profile = ProfileRegistry.resolveProfile(
            context = context,
            appId = appId,
            engineId = match.engine,
        ) ?: return Result.PackLoadFailure(match.engine)
        val container = WebViewContainer(
            id = appId,
            installPath = root.absolutePath,
            entryPoint = profile.entryPoint,
            engineProfile = match.engine,
            webRoot = match.webRoot,
            // "" so the pack default wins at launch.
            inputMap = "",
            // baseline for refreshEngineIfChanged. mtime read AFTER fingerprint so a directory write
            // during fingerprinting still triggers a re-check.
            fingerprintMtime = root.lastModified(),
            fingerprintedEngineId = match.engine,
            subEngine = match.subEngine,
        )
        WebViewContainer.save(slug, container)
        // a probe before the JSON existed may have cached a negative hit for this appId.
        app.gamenative.html5.host.WebViewScreenViewModel.invalidateSlugCache(appId)
        Timber.tag(TAG).i(
            "persisted slug=$slug engine=${match.engine} subEngine=${match.subEngine} " +
                "confidence=${match.confidence} alternates=${match.alternates}",
        )
        return Result.Matched
    }

    fun slugFor(appId: String): String? {
        // an existing container wins: it may still carry a legacy slug canonicalize() hasn't renamed,
        // and a derived name would point at a missing dir.
        app.gamenative.html5.host.WebViewScreenViewModel.slugFromAppId(appId)?.let { return it }
        val root = resolveFingerprintPath(appId) ?: return null
        return Html5SlugUtil.slug(root.name, appId)
    }

    private const val TAG = "Html5OptInService"
}
