package app.gamenative.ui.screen.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import app.gamenative.mods.NexusOAuthConfig

/**
 * The exact native redirect registered for GameNative with Nexus Mods.
 *
 * Keep this check in addition to the manifest intent filter. Exported activities can be
 * launched explicitly, bypassing their filters, and an OAuth authorization code must never
 * be accepted from a look-alike URI.
 */
internal object NexusOAuthCallbackContract {
    private val callbackUri = Uri.parse(NexusOAuthConfig.REDIRECT_URI)

    fun matches(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_VIEW && matches(intent.data)

    /** Returns a matching URI and removes all callback-bearing data from [intent]. */
    fun consumeAndScrub(intent: Intent): Uri? {
        val callbackUri = intent.takeIf(::matches)?.data
        intent.data = null
        intent.clipData = null
        intent.selector = null
        intent.replaceExtras(null as Bundle?)
        return callbackUri
    }

    fun matches(uri: Uri?): Boolean =
        uri != null &&
            uri.isHierarchical &&
            uri.scheme == callbackUri.scheme &&
            uri.encodedAuthority == callbackUri.encodedAuthority &&
            uri.encodedPath == callbackUri.encodedPath &&
            uri.fragment == null
}
