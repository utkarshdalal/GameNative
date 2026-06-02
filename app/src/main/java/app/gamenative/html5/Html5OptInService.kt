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
import app.gamenative.utils.CustomGameScanner
import com.winlator.container.ContainerData
import java.io.File
import timber.log.Timber
import app.gamenative.data.GameSource

// save-time html5 opt-in seam -- takes a ContainerData (not a Container) so the caller can still
// bail out pre-write on failure. WebViewContainer JSON persists BEFORE any Container mutation --
// if the caller later fails to apply the variant flip, the JSON is harmless orphan (Html5Routing
// reads it but no container points at it).
object Html5OptInService {

    sealed interface Result {
        data object Matched : Result
        data class NoMatch(val message: String) : Result
        data class CannotResolveInstallPath(val message: String) : Result
        // pack JSON failed to load -- fingerprint matched but ProfileRegistry returned null.
        // means assets/html5/packs/<engineId>.json is missing or unparseable; this is a
        // GameNative bug, not a user-facing condition. surface loudly.
        data class PackLoadFailure(val engineId: String) : Result
    }

    // resolves the install dir for fingerprinting per appId prefix:
    //   CUSTOM_GAME_<n>  → CustomGameScanner.getFolderPathFromAppId
    //   STEAM_<n>        → SteamService.getAppDirPath
    //   GOG_<n>          → GOGService.getInstallPath
    //   EPIC_<n>         → EpicService.getInstallPath
    //   AMAZON_<n>       → AmazonService.getInstallPathByAppId
    // callers surface CannotResolveInstallPath snackbar on null.
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

    // caller must dispatch on Dispatchers.IO (fingerprint + JSON I/O).
    // returns a typed Result; caller (PluviaMain.onSave) decides snackbar timing.
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
            // candidate engines (Godot/Unity/GameMaker HTML5) are recognized but unpacked --
            // surface the same NoMatch result so the caller's snackbar reads correctly. the
            // engineHint is logged via the message for diagnostic value.
            is FingerprintResult.Candidate -> return Result.NoMatch(
                context.getString(R.string.html5_optin_no_engine, root.absolutePath) +
                    " (looks like ${r.engineHint})",
            )
            FingerprintResult.Unknown -> return Result.NoMatch(
                context.getString(R.string.html5_optin_no_engine, root.absolutePath),
            )
        }

        val idPart = idPartFor(appId) ?: return Result.CannotResolveInstallPath(
            context.getString(R.string.html5_optin_install_path_unresolvable),
        )

        val slug = Html5SlugUtil.slug(root.name, idPart)
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
            // "" so pack default wins at launch (resolveInputMode falls through to
            // profile.input.mode). user override lands via GeneralTab dropdown.
            inputMap = "",
            // seed fingerprint cache so the install watcher can skip re-fingerprinting on
            // unchanged installs. mtime read AFTER fingerprint so a directory write during
            // fingerprint (rare -- assets re-extract from depot) still triggers re-check.
            fingerprintMtime = root.lastModified(),
            fingerprintedEngineId = match.engine,
            subEngine = match.subEngine,
        )
        WebViewContainer.save(slug, container)
        // drop any negative-hit cache entry from before the install completed (isHtml5App
        // probed before the JSON existed would have cached SENTINEL_NONE for this appId).
        app.gamenative.html5.host.WebViewScreenViewModel.invalidateSlugCache(appId)
        Timber.tag("Html5OptInService").i(
            "persisted slug=$slug engine=${match.engine} subEngine=${match.subEngine} " +
                "confidence=${match.confidence} alternates=${match.alternates}",
        )
        return Result.Matched
    }

    // idPart feeds jsonDirSlug -- stable unique int per container. CUSTOM_GAME_<n> uses sequential
    // custom-game id; STEAM_<appId> uses the Steam AppID directly. GOG product ids are numeric
    // strings; toIntOrNull is fine for the slug hash even if a future GOG id ever overflows Int
    // (none observed in the wild -- GOG ids are 10 digits, well under Int.MAX_VALUE). public so
    // Html5InstallWatcher can derive the same slug for cache lookup without duplicating the prefix
    // matrix.
    fun idPartFor(appId: String): Int? = when {
        GameSource.CUSTOM_GAME.matches(appId) -> GameSource.CUSTOM_GAME.idOf(appId).toIntOrNull()
        GameSource.STEAM.matches(appId) -> GameSource.STEAM.idOf(appId).toIntOrNull()
        GameSource.GOG.matches(appId) -> GameSource.GOG.idOf(appId).toIntOrNull()
        GameSource.EPIC.matches(appId) -> GameSource.EPIC.idOf(appId).toIntOrNull()
        GameSource.AMAZON.matches(appId) -> GameSource.AMAZON.idOf(appId).toIntOrNull()
        else -> null
    }

    // resolves the JSON-dir slug for a container without running the full opt-in flow. used by
    // Html5InstallWatcher to look up WebViewContainer.load(slug) for cache checks. returns null
    // when appId prefix is unsupported or path resolution fails.
    fun slugFor(appId: String): String? {
        val root = resolveFingerprintPath(appId) ?: return null
        val id = idPartFor(appId) ?: return null
        return Html5SlugUtil.slug(root.name, id)
    }
}
