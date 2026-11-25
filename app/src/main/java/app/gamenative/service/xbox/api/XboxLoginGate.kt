package app.gamenative.service.xbox.api

import android.app.Activity
import android.content.Context
import android.content.Intent
import app.gamenative.service.xbox.XboxHelperArchive
import app.gamenative.service.xbox.XboxTokenStore
import app.gamenative.service.xbox.XboxUser
import app.gamenative.service.xbox.live.LiveDeviceAuth
import app.gamenative.ui.screen.auth.XboxOAuthActivity
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber

/**
 * Prompts for the Microsoft account only when something needs it: installs the helper archive
 * (it holds the sign-in configuration), opens the sign-in activity and suspends until it closes.
 */
object XboxLoginGate {
    private class Pending(val store: XboxTokenStore, val result: CompletableDeferred<XboxUser?>)

    @Volatile private var pending: Pending? = null

    /** A stored account whose user STS token has not expired. */
    fun isSignedIn(store: XboxTokenStore): Boolean =
        store.user() != null && store.userStsToken()?.let { !LiveDeviceAuth.isExpired(it) } == true

    suspend fun ensureSignedIn(context: Context, store: XboxTokenStore): Result<XboxUser> {
        if (isSignedIn(store)) return Result.success(store.user()!!)
        runCatching { XboxHelperArchive.downloadAndExtract(context) {} }.onFailure {
            Timber.e(it, "Xbox sign-in: helper archive unavailable")
            return Result.failure(it)
        }
        val deferred = CompletableDeferred<XboxUser?>()
        pending = Pending(store, deferred)
        val intent = Intent(context, XboxOAuthActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        val user = deferred.await()
        pending = null
        return if (user != null) {
            Timber.i("Xbox signed in")
            Result.success(user)
        } else {
            Result.failure(IllegalStateException("Xbox sign-in canceled"))
        }
    }

    /** The token store of the sign-in in progress, for the activity. */
    fun pendingStore(): XboxTokenStore? = pending?.store

    /** Called by the sign-in activity when it closes; null means canceled or failed. */
    fun deliver(user: XboxUser?) {
        pending?.result?.complete(user)
    }
}
