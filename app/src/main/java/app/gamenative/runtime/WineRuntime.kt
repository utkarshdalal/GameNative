package app.gamenative.runtime

import com.winlator.container.Container

data object WineRuntime : GameRuntime {
    override val id: String = Container.RUNTIME_WINE
}
