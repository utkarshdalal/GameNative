package app.gamenative.html5.host

import android.content.Context
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container

// WHY: preLaunchApp + MainViewModel.launchApp + the LaunchApp dispatch all hit Wine-only
// code before the WebView dispatch branch runs. short-circuit so HTML5 titles skip Wine.
object Html5Routing {
    // runtime is the dispatch source of truth -- JSON existence alone
    // is insufficient -- user can flip variant html5→bionic, runtime→wine, but the
    // WebViewContainer JSON persists (cheaper toggle on flip-back, no re-fingerprint).
    fun isHtml5App(context: Context, appId: String): Boolean =
        ContainerUtils.resolveRuntime(context, appId) == Container.RUNTIME_WEBVIEW &&
            WebViewScreenViewModel.slugFromAppId(appId) != null
}
