package app.gamenative.html5.host

import android.content.Context
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container

// launch paths hit Wine-only code before the WebView dispatch runs; this lets them short-circuit.
object Html5Routing {
    // runtime is the source of truth, not JSON existence: the WebViewContainer JSON survives a flip to wine.
    fun isHtml5App(context: Context, appId: String): Boolean =
        ContainerUtils.resolveRuntime(context, appId) == Container.RUNTIME_WEBVIEW &&
            WebViewScreenViewModel.slugFromAppId(appId) != null
}
