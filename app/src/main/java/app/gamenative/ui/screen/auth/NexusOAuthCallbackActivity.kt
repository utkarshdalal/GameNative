package app.gamenative.ui.screen.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.gamenative.MainActivity
import app.gamenative.R
import app.gamenative.mods.NexusAuthManager
import app.gamenative.ui.util.SnackbarManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Minimal trampoline for the browser-based Nexus OAuth redirect.
 *
 * The callback URI is scrubbed from both the source intent and the Activity's retained intent
 * before any suspension point. This prevents Android from replaying an authorization code after
 * recreation or exposing it through a later inspection of the launch intent.
 */
class NexusOAuthCallbackActivity : ComponentActivity() {
    private companion object {
        var callbackJob: Job? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null && !NexusOAuthCallbackContract.matches(intent)) {
            NexusOAuthCallbackContract.consumeAndScrub(intent)
            setIntent(Intent(this, NexusOAuthCallbackActivity::class.java))
            returnToApp()
            return
        }
        receiveCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveCallback(intent)
    }

    private fun receiveCallback(sourceIntent: Intent) {
        val callbackUri = NexusOAuthCallbackContract.consumeAndScrub(sourceIntent)
        setIntent(Intent(this, NexusOAuthCallbackActivity::class.java))

        if (callbackJob?.isActive == true) {
            Timber.w("[NexusOAuth]: Ignoring a second callback while one is already being processed")
            return
        }
        if (callbackUri == null) {
            Timber.w("[NexusOAuth]: Rejected an intent that did not match the registered redirect")
            SnackbarManager.show(getString(R.string.nexus_oauth_invalid_callback))
            returnToApp()
            return
        }
        callbackJob = lifecycleScope.launch {
            try {
                withContext(NonCancellable) {
                    NexusAuthManager.handleAuthorizationCallback(callbackUri)
                        .onSuccess { account ->
                            if (account != null) {
                                SnackbarManager.show(
                                    getString(R.string.nexus_oauth_connected_as, account.name),
                                )
                            }
                        }
                        .onFailure { error ->
                            // Do not log exception messages here: OAuth server errors can include
                            // callback details that do not belong in application logs.
                            Timber.w(
                                "[NexusOAuth]: Browser sign-in did not complete (%s)",
                                error.javaClass.simpleName,
                            )
                            SnackbarManager.show(getString(R.string.nexus_oauth_sign_in_failed))
                        }
                    returnToApp()
                }
            } finally {
                callbackJob = null
            }
        }
    }

    private fun returnToApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
        )
        finish()
    }
}
