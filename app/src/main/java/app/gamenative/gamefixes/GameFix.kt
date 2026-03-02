package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource

interface GameFix {
    fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
    ): Boolean
}

/**
 * A [GameFix] that declares its registry key (source + id) so the registry
 * can build the map automatically without repeating the id.
 */
interface KeyedGameFix : GameFix {
    val gameSource: GameSource
    val gameId: String
}
