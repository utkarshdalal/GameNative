package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import timber.log.Timber

class BionicSteamClientFix : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        return try {
            if (!container.containerVariant.equals(Container.BIONIC, ignoreCase = true)) {
                // The bionic Steam bridge is only bootstrapped by the bionic launcher.
                // On a glibc container the flag would have no launcher support, so leave it off.
                Timber.tag("GameFixes").i("Skipping bionic Steam client for game $gameId on non-bionic container")
                return true
            }

            if (container.isLaunchBionicSteam) {
                return true
            }

            container.setLaunchBionicSteam(true)
            container.setLaunchRealSteam(false)
            container.saveData()
            Timber.tag("GameFixes").i("Enabled bionic Steam client for game $gameId")
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to enable bionic Steam client for game $gameId")
            false
        }
    }
}

class KeyedBionicSteamClientFix(
    override val gameSource: GameSource,
    override val gameId: String,
) : KeyedGameFix, GameFix by BionicSteamClientFix()
