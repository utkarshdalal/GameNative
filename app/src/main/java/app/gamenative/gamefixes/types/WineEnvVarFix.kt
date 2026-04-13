package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import timber.log.Timber

class WineEnvVarFix(
    private val envVarsToSet: Map<String, String>,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        return try {
            val envVars = EnvVars(container.envVars)
            var hasChanges = false

            for ((name, value) in envVarsToSet) {
                if (envVars.has(name)) {
                    // Do not override env vars when the user already configured them.
                    continue
                }
                envVars.put(name, value)
                hasChanges = true
            }

            if (!hasChanges) {
                return true
            }

            container.envVars = envVars.toString()
            container.saveData()
            Timber.tag("GameFixes").i("Added env vars '$envVarsToSet' for game $gameId")
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to add env vars '$envVarsToSet' for game $gameId")
            false
        }
    }
}

class KeyedWineEnvVarFix(
    override val gameSource: GameSource,
    override val gameId: String,
    envVarsToSet: Map<String, String>,
) : KeyedGameFix, GameFix by WineEnvVarFix(envVarsToSet)
