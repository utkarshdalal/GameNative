package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import timber.log.Timber

/**
 * Stardew Valley (Steam)
 */
val STEAM_Fix_413150: KeyedGameFix = object : KeyedGameFix {
    override val gameSource = GameSource.STEAM
    override val gameId = "413150"

    private val bionicFix = WineEnvVarFix(mapOf("WINEDLLOVERRIDES" to "icu=n"))

    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        if (container.containerVariant.equals(Container.BIONIC, ignoreCase = true)) {
            return bionicFix.apply(context, gameId, installPath, installPathWindows, container)
        }

        return try {
            val envVars = EnvVars(container.envVars)
            if (envVars.get("WINEDLLOVERRIDES") != "icu=n") return true

            // The native ICU override depends on libraries shipped only by the bionic image.
            // Remove values previously installed by this fix when a container uses glibc.
            envVars.remove("WINEDLLOVERRIDES")
            container.envVars = envVars.toString()
            container.saveData()
            Timber.tag("GameFixes").i("Removed incompatible Stardew Valley ICU override from glibc container")
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to remove Stardew Valley ICU override from glibc container")
            false
        }
    }
}
