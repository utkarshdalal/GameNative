package app.gamenative.html5.host

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// launch paths hit Wine-only code before the WebView dispatch runs; this lets them short-circuit.
object Html5Routing {
    // runtime is the source of truth, not JSON existence: the WebViewContainer JSON survives a flip to wine.
    fun isHtml5App(context: Context, appId: String): Boolean =
        ContainerUtils.resolveRuntime(context, appId) == Container.RUNTIME_WEBVIEW &&
            WebViewScreenViewModel.slugFromAppId(appId) != null

    // both lookups read container configs, so composables resolve them on IO; wine until resolved.
    @Composable
    fun rememberIsHtml5App(context: Context, appId: String): Boolean =
        produceState(false, appId) {
            value = withContext(Dispatchers.IO) { isHtml5App(context, appId) }
        }.value

    // re-resolves on LibraryInstallStatusChanged, which applyToContainerGated emits after a runtime change.
    @Composable
    fun rememberRuntime(context: Context, appId: String, gameId: Int): String {
        var refresh by remember(appId) { mutableIntStateOf(0) }
        DisposableEffect(appId) {
            val listener: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
                if (event.appId == gameId) refresh++
            }
            PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(listener)
            onDispose { PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(listener) }
        }
        return produceState(Container.RUNTIME_WINE, appId, refresh) {
            value = withContext(Dispatchers.IO) { ContainerUtils.resolveRuntime(context, appId) }
        }.value
    }
}
