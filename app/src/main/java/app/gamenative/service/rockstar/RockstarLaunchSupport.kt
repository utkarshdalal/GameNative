package app.gamenative.service.rockstar

import android.content.Context
import java.io.File
import timber.log.Timber

/** Installed-file detection and credential handoff. Deployment lives in RockstarHelperDeployment. */
object RockstarLaunchSupport {

    fun isRockstarTitle(gameDir: File) = RockstarHelperArchive.usesRockstar(gameDir)

    /**
     * Whether the game directory already holds a token the stub can use. A prefix set up by hand
     * has one, and it signs in perfectly well, so a sign-in that does not complete must not stop
     * that launch.
     */
    fun hasUsableToken(gameDir: File): Boolean = runCatching {
        val f = File(gameDir, RockstarConstants.TOKEN_FILE)
        f.exists() && RockstarConstants.TOKEN_SHAPE.matches(f.readText().trim())
    }.getOrDefault(false)

    /**
     * Writes the stored ScAuthToken where rgscstub.ini's `tokenfile` points.
     *
     * Any existing token is copied aside first. Where the token surfaces in the sign-in flow is
     * not yet confirmed, so a capture that looks right but is not would otherwise overwrite a
     * working token and leave the game unable to sign in with no way back.
     */
    fun placeToken(context: Context, gameDir: File): Boolean {
        val creds = RockstarAuthManager.load(context) ?: run {
            Timber.w("Rockstar: no stored session, cannot place the token")
            return false
        }
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
