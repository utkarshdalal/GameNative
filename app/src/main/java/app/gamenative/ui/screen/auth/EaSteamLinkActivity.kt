package app.gamenative.ui.screen.auth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.gamenative.service.ea.EaConstants
import app.gamenative.service.ea.EaSteamLinkGate
import app.gamenative.ui.component.dialog.AuthWebViewDialog
import app.gamenative.ui.theme.PluviaTheme
import timber.log.Timber

/**
 * EA's connections page, where the user links Steam through Steam's own web sign-in. The EA
 * session cookies from the sign-in WebView are shared, so the page opens signed in. There is no
 * redirect to catch: the user closes the window when the link is made.
 */
class EaSteamLinkActivity : ComponentActivity() {
    private var finished = false

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !finished) { finished = true; EaSteamLinkGate.deliver(true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PluviaTheme {
                AuthWebViewDialog(
                    isVisible = true,
                    url = EaConstants.ACCOUNT_CONNECTIONS_URL,
                    onUrlChange = { Timber.i("EA Steam link: %s", it.substringBefore('?')) },
                    onDismissRequest = {
                        if (!finished) {
                            finished = true
                            EaSteamLinkGate.deliver(true)
                            finish()
                        }
                    },
                )
            }
        }
    }
}
