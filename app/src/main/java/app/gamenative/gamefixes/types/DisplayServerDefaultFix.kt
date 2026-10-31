package app.gamenative.gamefixes

import android.content.Context
import com.winlator.container.Container
import timber.log.Timber

/**
 * Picks the display server a game is known to need, once per container: a display server the user
 * chooses afterwards is kept.
 */
class DisplayServerDefaultFix(
    private val displayServer: String,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        return try {
            if (container.getExtra(APPLIED_EXTRA) == displayServer) {
                return true
            }
            if (!container.displayServer.equals(displayServer, ignoreCase = true)) {
                container.displayServer = displayServer
                Timber.tag("GameFixes").i("Set display server '$displayServer' for game $gameId")
            }
            container.putExtra(APPLIED_EXTRA, displayServer)
            container.saveData()
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to set display server '$displayServer' for game $gameId")
            false
        }
    }

    private companion object {
        const val APPLIED_EXTRA = "displayServerDefaultFixApplied"
    }
}
