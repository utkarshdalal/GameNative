package app.gamenative.runtime

import androidx.annotation.VisibleForTesting
import timber.log.Timber

// outside the compose collector so unit tests don't need a navhost.
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
fun dispatchLaunchByRuntime(
    runtime: GameRuntime,
    appId: String,
    navigateToWine: () -> Unit,
    navigateToWebView: () -> Unit,
) {
    when (runtime) {
        WineRuntime -> navigateToWine()
        WebViewRuntime -> {
            Timber.i("html5 runtime dispatched for app $appId — navigating to WebViewScreen")
            navigateToWebView()
        }
    }
}

// expectedRoute ignores a back that fired after the user already navigated elsewhere. external-intent
// launches finish() instead of popping so the caller's task stack stays intact.
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
fun dispatchNavigateBack(
    expectedRoute: String,
    currentRoute: String?,
    wasLaunchedViaExternalIntent: Boolean,
    finishActivity: () -> Unit,
    popBackStack: () -> Unit,
    clearExternalIntentFlag: () -> Unit,
) {
    if (currentRoute != expectedRoute) return
    if (wasLaunchedViaExternalIntent) {
        Timber.d("[IntentLaunch]: Finishing activity to return to external launcher ($expectedRoute)")
        clearExternalIntentFlag()
        finishActivity()
    } else {
        popBackStack()
    }
}

// in-session html5 exit. ALWAYS pops, even for external-intent launches: save export hangs off the
// WebView's onDispose, and finish() here would cancel exitSteamApp's viewModelScope before the cloud
// upload runs. the finish rides exitSteamApp's onComplete instead (webViewExitCompletion). pre-session
// failures keep dispatchNavigateBack: no teardown follows them, so nothing else would finish the activity.
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
fun dispatchWebViewSessionExit(
    expectedRoute: String,
    currentRoute: String?,
    popBackStack: () -> Unit,
) {
    if (currentRoute != expectedRoute) return
    popBackStack()
}

// null for an in-app launch -- the pop already returned to the library.
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
fun webViewExitCompletion(
    wasLaunchedViaExternalIntent: Boolean,
    finishActivity: () -> Unit,
    clearExternalIntentFlag: () -> Unit,
): (() -> Unit)? {
    if (!wasLaunchedViaExternalIntent) return null
    return {
        Timber.d("[IntentLaunch]: exit handling done, returning to external launcher (webview)")
        clearExternalIntentFlag()
        finishActivity()
    }
}
