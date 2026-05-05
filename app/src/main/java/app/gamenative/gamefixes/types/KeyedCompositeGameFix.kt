package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container

/**
 * Applies multiple fixes for the same game key.
 * Every fix in [fixes] runs in order.
 */
class CompositeGameFix(
    private val fixes: List<GameFix>,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        var allSucceeded = true
        for (fix in fixes) {
            val succeeded = fix.apply(context, gameId, installPath, installPathWindows, container)
            if (!succeeded) {
                allSucceeded = false
            }
        }
        return allSucceeded
    }
}

class KeyedCompositeGameFix(
    override val gameSource: GameSource,
    override val gameId: String,
    fixes: List<GameFix>,
) : KeyedGameFix, GameFix by CompositeGameFix(fixes)
