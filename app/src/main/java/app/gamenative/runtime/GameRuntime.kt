package app.gamenative.runtime

import com.winlator.container.Container

// sealed so when-branches at dispatch sites are compiler-checked (adding a future variant
// breaks the build at every call site that forgets the new branch -- no silent else fallthrough).
sealed interface GameRuntime {
    val id: String

    companion object {
        // bridge from the persisted string id (Container.runtime -- Java JSON field) to the
        // sealed type. unknown / blank ids default to wine for back-compat with pre-html5
        // containers. Container.normalizeRuntime already canonicalizes on load/setter, so
        // the string compare below is the single place unknown-string handling lives.
        // wine is deliberately the fallback (unknown / blank / RUNTIME_WINE all resolve to it).
        fun fromId(id: String): GameRuntime =
            if (id == Container.RUNTIME_WEBVIEW) WebViewRuntime else WineRuntime
    }
}
