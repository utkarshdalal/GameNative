package app.gamenative.service.ea

import android.app.Activity
import android.content.Context
import android.content.Intent
import app.gamenative.ui.screen.auth.EaOAuthActivity
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber

/**
 * Prompts for the EA account only when a launch needs it: opens the sign-in activity and
 * suspends the launch until the code comes back (or the user dismisses it).
 */
object EaLoginGate {
    @Volatile private var pending: CompletableDeferred<String?>? = null

    suspend fun ensureSignedIn(context: Context): Result<Unit> {
        if (EaAuthManager.isLoggedIn(context)) return Result.success(Unit)
        val deferred = CompletableDeferred<String?>()
        pending = deferred
        val intent = Intent(context, EaOAuthActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        val code = deferred.await()
        pending = null
        if (code.isNullOrEmpty()) return Result.failure(IllegalStateException("EA sign-in cancelled"))
        return EaAuthManager.authenticateWithCode(context, code).map { Timber.i("EA signed in as ${it.displayName}") }
    }

    /** Called by the sign-in activity when it closes; null means cancelled. */
    fun deliver(code: String?) {
        pending?.complete(code)
    }
}
