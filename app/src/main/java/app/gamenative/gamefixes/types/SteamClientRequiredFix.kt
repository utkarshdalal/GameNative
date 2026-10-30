package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import timber.log.Timber

/**
 * For games whose DRM only a genuine Steam client can satisfy, e.g. Steam CEG (Custom Executable
 * Generation). CEG executables are signed per account and verify themselves through steamclient
 * at runtime, so neither Steamless nor the emulated Steam client can make them start; they sit on
 * a black screen instead. Uses bionic Steam on bionic containers, the Windows Steam client otherwise.
 * A launch mode the user already picked is left alone.
 */
class SteamClientRequiredFix : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        return try {
            if (container.isLaunchBionicSteam || container.isLaunchRealSteam) {
                return true
            }

            if (container.containerVariant.equals(Container.BIONIC, ignoreCase = true)) {
                container.setLaunchBionicSteam(true)
                container.setLaunchRealSteam(false)
                Timber.tag("GameFixes").i("Enabled bionic Steam client for game $gameId (Steam client required)")
            } else {
                container.setLaunchRealSteam(true)
                container.setLaunchBionicSteam(false)
                Timber.tag("GameFixes").i("Enabled real Steam client for game $gameId (Steam client required)")
            }
            container.saveData()
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to enable a Steam client for game $gameId")
            false
        }
    }
}

class KeyedSteamClientRequiredFix(
    override val gameSource: GameSource,
    override val gameId: String,
) : KeyedGameFix, GameFix by SteamClientRequiredFix()
