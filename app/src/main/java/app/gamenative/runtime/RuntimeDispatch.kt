package app.gamenative.runtime

import androidx.annotation.VisibleForTesting
import timber.log.Timber

// testable dispatch seam -- pulled out of the compose collector so unit tests don't need navhost.
// takes sealed GameRuntime (not a raw string) so a new variant forces a compile error here.
// callers resolve container.runtime via GameRuntime.fromId(...); see GameRuntime for the
// unknown-string / back-compat fallback rationale.
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

// exit-side counterpart to dispatchLaunchByRuntime. XServerScreen and WebViewScreen pass
// this as their navigateBack callback. expectedRoute guards against acting on a back that
// fired after the user already navigated elsewhere; the route equality check mirrors the
// in-line block this replaced. external-intent launches finish() the activity instead of
// popping the back stack so the caller's task stack stays intact.
//
// finishActivity / popBackStack are passed as lambdas so the function stays
// android.app.Activity / NavController-free for unit testability.
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
