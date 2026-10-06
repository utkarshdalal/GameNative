package app.gamenative.service.ea

import android.app.Activity
import android.content.Context
import android.content.Intent
import app.gamenative.ui.screen.auth.EaSteamLinkActivity
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber

/**
 * EA attaches Steam purchases to an EA account only once the Steam account is linked to it, and
 * the link is made on EA's account site through Steam's web sign-in, not with a Steam ticket.
 * When the storefront refresh shows no Steam link, this opens that page before the launch so a
 * first run can be entitled, then refreshes again once the user closes it. It never blocks the
 * launch: a linked account with no offers looks the same, and the licence request at
 * PrepareLaunch is the check that speaks for the game.
 */
object EaSteamLinkGate {
    @Volatile private var pending: CompletableDeferred<Boolean>? = null

    suspend fun offerSteamLink(context: Context) {
        val creds = EaAuthManager.credentials(context) ?: return
        val before = EaLicenseManager.refreshExternalEntitlements(context, creds.userId)
        if (before == null) {
            Timber.w("EA storefront refresh failed; cannot tell whether Steam is linked")
            return
        }
        if (EaLicenseManager.mentionsSteam(before)) return
        Timber.i("EA account ${creds.displayName} shows no Steam link; opening the connections page")
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        val intent = Intent(context, EaSteamLinkActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        try { deferred.await() } finally { if (pending === deferred) pending = null }
        val after = EaLicenseManager.refreshExternalEntitlements(context, creds.userId)
        Timber.i("EA Steam link after the connections page: %s", if (EaLicenseManager.mentionsSteam(after)) "present" else "still absent")
    }

    /** Called by the link activity when it closes. */
    fun deliver(closed: Boolean) {
        pending?.complete(closed)
    }
}
