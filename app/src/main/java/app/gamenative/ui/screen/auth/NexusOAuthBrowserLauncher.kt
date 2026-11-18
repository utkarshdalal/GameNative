package app.gamenative.ui.screen.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** Opens only the Nexus authorization endpoint used by the native OAuth flow. */
internal object NexusOAuthBrowserLauncher {
    private const val AUTHORIZATION_HOST = "users.nexusmods.com"
    private const val AUTHORIZATION_PATH = "/oauth/authorize"

    fun launch(context: Context, authorizationUri: Uri): Result<Unit> = runCatching {
        require(isAllowedAuthorizationUri(authorizationUri)) {
            "Refusing to open an unexpected Nexus authorization URL"
        }
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()
            .launchUrl(context, authorizationUri)
    }

    internal fun isAllowedAuthorizationUri(uri: Uri): Boolean =
        uri.isHierarchical &&
            uri.scheme == "https" &&
            uri.encodedAuthority == AUTHORIZATION_HOST &&
            uri.encodedPath == AUTHORIZATION_PATH &&
            uri.fragment == null
}
