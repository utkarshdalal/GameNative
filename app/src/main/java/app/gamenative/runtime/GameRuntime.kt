package app.gamenative.runtime

import com.winlator.container.Container

// sealed so a new variant breaks the build at every dispatch site that forgets it.
sealed interface GameRuntime {
    val id: String

    companion object {
        // unknown/blank ids deliberately resolve to wine so pre-html5 containers keep working.
        fun fromId(id: String): GameRuntime =
            if (id == Container.RUNTIME_WEBVIEW) WebViewRuntime else WineRuntime
    }
}
