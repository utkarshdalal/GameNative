package app.gamenative.service.ubisoft

import android.app.Activity
import android.content.Context
import android.content.Intent
import app.gamenative.service.SteamService
import app.gamenative.ui.screen.auth.UbisoftOAuthActivity
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber

/**
 * Prompts for the Ubisoft account only when a launch needs it, as the Rockstar gate does: a stored
 * session is refreshed when it is close to expiry, and the sign-in window opens only when there is
 * none or the server has explicitly refused it.
 */
object UbisoftLoginGate {
    @Volatile private var pending: CompletableDeferred<UbisoftWebLogin?>? = null

    suspend fun ensureSignedIn(context: Context): Result<Unit> {
        when (val fresh = UbisoftAuthManager.ensureFresh(context)) {
            is UbisoftAuthManager.Freshness.Ready -> return Result.success(Unit)
            is UbisoftAuthManager.Freshness.Unavailable -> return Result.failure(IllegalStateException(fresh.reason))
            UbisoftAuthManager.Freshness.NeedsSignIn -> Unit
        }
        val deferred = CompletableDeferred<UbisoftWebLogin?>()
        pending = deferred
        // With the sign-in window in front MainActivity would stop the Steam service as idle, and
        // the launch still needs it for the app ticket.
        val keepAliveBefore = SteamService.keepAlive
        SteamService.keepAlive = true
        val intent = Intent(context, UbisoftOAuthActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        val login = try { deferred.await() } finally {
            if (pending === deferred) pending = null
            SteamService.keepAlive = keepAliveBefore
        }
        if (login == null) return Result.failure(IllegalStateException("Ubisoft sign-in canceled"))
        return UbisoftAuthManager.signIn(context, login).map { Timber.i("Ubisoft signed in") }
    }

    /** Called by the sign-in activity when it closes; null means canceled. */
    fun deliver(login: UbisoftWebLogin?) {
        pending?.complete(login)
    }
}
