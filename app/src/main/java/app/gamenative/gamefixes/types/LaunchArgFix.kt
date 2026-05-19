package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import timber.log.Timber
import com.winlator.container.Container

class LaunchArgFix(
    private val launchArgs: String,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        return try {
            val currentArgs = container.execArgs.trim()
            if (currentArgs.isNotEmpty()) {
                // Do not override / append when the user already configured custom launch args.
                return true
            }

            container.execArgs = launchArgs
            container.saveData()
            Timber.tag("GameFixes").i("Added launch args '$launchArgs' for game $gameId")
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to add launch args '$launchArgs' for game $gameId")
            false
        }
    }
}

class KeyedLaunchArgFix(
    override val gameSource: GameSource,
    override val gameId: String,
    launchArgs: String,
) : KeyedGameFix, GameFix by LaunchArgFix(launchArgs)
