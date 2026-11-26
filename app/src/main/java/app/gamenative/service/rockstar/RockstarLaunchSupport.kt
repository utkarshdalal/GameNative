package app.gamenative.service.rockstar

import android.content.Context
import app.gamenative.NetworkMonitor
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.service.epic.EpicService
import com.winlator.container.Container
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Installed-file detection and credential handoff. Deployment lives in RockstarHelperDeployment. */
object RockstarLaunchSupport {

    fun isRockstarTitle(gameDir: File) = RockstarHelperArchive.usesRockstar(gameDir)

    /**
     * An Epic copy goes through the stand-in only when its metadata names the title's Epic client
     * and the installed helper can link Epic accounts; otherwise it starts its own executable.
     */
    fun servesEpicCopy(filesDir: File, installDir: File): Boolean =
        isRockstarTitle(installDir) && RockstarHelperArchive.supportsEpicCopies(filesDir) &&
            RockstarTitleMetadata.find(installDir)?.let { RockstarTitleMetadata.parse(it) }?.epic != null

    /** An Epic copy whose metadata names the title's Epic client: the launch that needs the helper downloaded. */
    fun epicCopyNeedsHelper(gameId: Int): Boolean =
        EpicService.getEpicGameOf(gameId)?.installPath?.takeIf { it.isNotEmpty() }?.let(::File)
            ?.let { isRockstarTitle(it) && RockstarTitleMetadata.find(it)?.let { file -> RockstarTitleMetadata.parse(file) }?.epic != null } == true

    fun servedSteamLaunch(container: Container, gameId: Int): Pair<File, RockstarStore>? =
        if (container.isLaunchHeadlessSteam || container.isLaunchBionicSteam) {
            File(SteamService.getAppDirPath(gameId)).takeIf(::isRockstarTitle)?.let { it to RockstarStore.Steam(gameId) }
        } else {
            null
        }

    /** The game directory and store of a launch the stand-in serves, found from the installed files. */
    fun servedLaunch(filesDir: File, container: Container, gameSource: GameSource, gameId: Int): Pair<File, RockstarStore>? = when (gameSource) {
        GameSource.STEAM -> servedSteamLaunch(container, gameId)
        GameSource.EPIC -> EpicService.getEpicGameOf(gameId)?.installPath?.takeIf { it.isNotEmpty() }
            ?.let(::File)?.takeIf { servesEpicCopy(filesDir, it) }?.let { it to RockstarStore.Epic(gameId) }
        else -> null
    }

    /**
     * Everything a launch through the stand-in needs before the game starts: the helper, the
     * Rockstar sign-in (with the store account linked and the title activated when the stub
     * asked for it), the Social Club runtime in the prefix, the token and the staged helper.
     * The stub mints the ROS ticket with CreateTicketScAuthToken2 and exits when the token file
     * is missing, so the account sign-in is required rather than a convenience.
     * Returns the message to show when the launch cannot go ahead.
     */
    suspend fun prepareLaunch(
        context: Context,
        gameDir: File,
        prefixDriveC: File,
        store: RockstarStore,
        setLoadingMessage: (String) -> Unit,
        setLoadingProgress: (Float) -> Unit,
    ): String? {
        setLoadingMessage(context.getString(R.string.rockstar_preparing))
        RockstarHelperArchive.downloadAndExtract(context) { setLoadingProgress(it) }
        val activeTitle = RockstarTitleMetadata.find(gameDir)?.let { RockstarTitleMetadata.parse(it) }?.titleId
            ?.takeIf { it.matches(Regex("[a-z0-9_]{1,127}")) } ?: "launcher"
        val activationMarker = File(
            RockstarHelperArchive.titleDir(gameDir),
            "${RockstarHelperDeployment.DIRECTORY}/${RockstarConstants.ACTIVATION_MARKER}",
        )
        /* The launch flag is also set before the Steam logon finishes; the sign-in page only needs the network. */
        val noInternet = !NetworkMonitor.hasInternet.value
        if (noInternet && (activationMarker.isFile || !(RockstarAuthManager.isLoggedIn(context) || hasUsableToken(gameDir)))) {
            /* The sign-in page cannot load without a connection, so do not open it. */
            return context.getString(R.string.rockstar_offline_sign_in_required)
        }
        val signIn = if (noInternet) {
            Result.success(Unit)
        } else {
            RockstarLoginGate.ensureSignedIn(context, activeTitle, store, activationMarker.isFile)
        }
        if (!noInternet && signIn.isSuccess && RockstarLoginGate.titleValidated) activationMarker.delete()
        if (signIn.isFailure && (RockstarAuthManager.isLoggedIn(context) || hasUsableToken(gameDir))) {
            /*
             * The stub deletes the token file once it has read it, so a stored session counts too:
             * placeToken writes it again below. Carry on rather than block a launch that works.
             */
            Timber.tag("preLaunchApp").w("Rockstar sign-in did not complete; using the stored session or the token already in place")
        } else if (signIn.isFailure) {
            Timber.tag("preLaunchApp").w(signIn.exceptionOrNull(), "Rockstar sign-in did not complete")
            return context.getString(R.string.rockstar_login_failed, signIn.exceptionOrNull()?.message ?: "")
        }
        if (!RockstarRuntime.isInstalled(prefixDriveC)) {
            val installer = RockstarRuntime.installer(gameDir) ?: return context.getString(R.string.rockstar_installer_missing)
            setLoadingMessage(context.getString(R.string.rockstar_runtime_installing))
            withContext(Dispatchers.IO) { RockstarRuntime.install(context, installer, prefixDriveC) { setLoadingProgress(it) } }
        }
        withContext(Dispatchers.IO) { RockstarRuntime.ensureX86(context, RockstarRuntime.installer(gameDir), prefixDriveC) { setLoadingProgress(it) } }
        return try {
            withContext(Dispatchers.IO) {
                check(placeToken(context, gameDir) || hasUsableToken(gameDir)) {
                    "Could not place Rockstar sign-in credentials in the game directory"
                }
                RockstarHelperDeployment.prepare(context.filesDir, gameDir)
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("preLaunchApp").e(e, "Rockstar launcher setup failed")
            e.message ?: e.toString()
        }
    }

    /**
     * Removes tokens placeToken wrote into the game directories. Signing out has to clear these
     * too: preLaunchApp falls back to a token already in place when a sign-in does not complete,
     * so leaving them behind keeps the previous account working.
     */
    suspend fun clearPlacedTokens(context: Context) = withContext(Dispatchers.IO) {
        val steam = SteamService.getAllInstalledApps().orEmpty().map { File(SteamService.getAppDirPath(it.id)) }
        val epic = if (RockstarHelperArchive.supportsEpicCopies(context.filesDir)) RockstarStore.epicInstallDirs() else emptyList()
        for (installDir in steam + epic) {
            if (!isRockstarTitle(installDir)) continue
            val gameDir = RockstarHelperArchive.titleDir(installDir)
            for (name in listOf(RockstarConstants.TOKEN_FILE, RockstarConstants.TOKEN_FILE + ".previous")) {
                val file = File(gameDir, name)
                if (file.exists() && file.delete()) Timber.i("Rockstar: removed %s from %s", name, gameDir.name)
            }
        }
    }

    /**
     * Whether the game directory already holds a token the stub can use. A prefix set up by hand
     * has one, and it signs in perfectly well, so a sign-in that does not complete must not stop
     * that launch.
     */
    fun hasUsableToken(gameDir: File): Boolean = runCatching {
        val f = File(RockstarHelperArchive.titleDir(gameDir), RockstarConstants.TOKEN_FILE)
        f.exists() && RockstarConstants.TOKEN_SHAPE.matches(f.readText().trim())
    }.getOrDefault(false)

    /**
     * Writes the stored ScAuthToken where rgscstub.ini's `tokenfile` points.
     *
     * Any existing token is copied aside first. Where the token surfaces in the sign-in flow is
     * not yet confirmed, so a capture that looks right but is not would otherwise overwrite a
     * working token and leave the game unable to sign in with no way back.
     */
    fun placeToken(context: Context, installDir: File): Boolean {
        val creds = RockstarAuthManager.load(context) ?: run {
            Timber.w("Rockstar: no stored session, cannot place the token")
            return false
        }
        val gameDir = RockstarHelperArchive.titleDir(installDir)
        val target = File(gameDir, RockstarConstants.TOKEN_FILE)
        return runCatching {
            if (target.exists()) {
                val existing = target.readText().trim()
                if (existing == creds.scAuthToken) {
                    Timber.i("Rockstar: token already current, left alone")
                    return true
                }
                val backup = File(gameDir, RockstarConstants.TOKEN_FILE + ".previous")
                target.copyTo(backup, overwrite = true)
                Timber.i("Rockstar: previous token kept as %s", backup.name)
            }
            target.writeText(creds.scAuthToken)
            Timber.i("Rockstar: token placed in %s (%d chars)", gameDir.name, creds.scAuthToken.length)
            true
        }.onFailure { Timber.e(it, "Rockstar: could not write the token") }.getOrDefault(false)
    }

}
