package app.gamenative.runtime

import com.winlator.container.Container

data object WebViewRuntime : GameRuntime {
    override val id: String = Container.RUNTIME_WEBVIEW
}
