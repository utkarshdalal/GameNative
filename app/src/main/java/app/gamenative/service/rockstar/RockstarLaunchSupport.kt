package app.gamenative.service.rockstar

import android.content.Context
import app.gamenative.service.SteamService
import java.io.File
import timber.log.Timber

/** Installed-file detection and credential handoff. Deployment lives in RockstarHelperDeployment. */
object RockstarLaunchSupport {

    fun isRockstarTitle(gameDir: File) = RockstarHelperArchive.usesRockstar(gameDir)

    private fun titleDir(installDir: File) = RockstarHelperArchive.titleDir(installDir) ?: installDir

    /**
     * Removes tokens placeToken wrote into the game directories. Signing out has to clear these
     * too: preLaunchApp falls back to a token already in place when a sign-in does not complete,
     * so leaving them behind keeps the previous account working.
     */
    fun clearPlacedTokens() {
        val installed = SteamService.getAllInstalledApps() ?: return
        for (app in installed) {
            val gameDir = RockstarHelperArchive.titleDir(File(SteamService.getAppDirPath(app.id))) ?: continue
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
        val f = File(titleDir(gameDir), RockstarConstants.TOKEN_FILE)
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
        val gameDir = titleDir(installDir)
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
