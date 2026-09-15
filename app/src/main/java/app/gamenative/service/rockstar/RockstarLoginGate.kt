package app.gamenative.service.rockstar

import android.app.Activity
import android.content.Context
import android.content.Intent
import app.gamenative.ui.screen.auth.RockstarOAuthActivity
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber

/**
 * Prompts for the Rockstar account only when a launch needs it, the same way the EA gate does:
 * opens the sign-in activity and suspends the launch until a token comes back, or the user
 * dismisses it.
 */
object RockstarLoginGate {
    @Volatile private var pending: CompletableDeferred<String?>? = null

    suspend fun ensureSignedIn(context: Context, activeTitle: String): Result<Unit> {
        require(activeTitle.matches(Regex("[a-z0-9_]{1,127}")))
        if (RockstarAuthManager.isLoggedIn(context)) return Result.success(Unit)
        val deferred = CompletableDeferred<String?>()
        pending = deferred
        val intent = Intent(context, RockstarOAuthActivity::class.java)
            .putExtra(RockstarConstants.ACTIVE_TITLE_EXTRA, activeTitle)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        val token = try { deferred.await() } finally { if (pending === deferred) pending = null }
        if (token.isNullOrEmpty()) return Result.failure(IllegalStateException("Rockstar sign-in cancelled"))
        RockstarAuthManager.store(context, token)
        Timber.i("Rockstar signed in")
        return Result.success(Unit)
    }

    /** Called by the sign-in activity when it closes; null means cancelled. */
    fun deliver(token: String?) {
        pending?.complete(token)
    }
}
