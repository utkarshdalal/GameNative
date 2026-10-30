package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import timber.log.Timber

/**
 * For games whose DRM only a genuine Steam client can satisfy, e.g. Steam CEG (Custom Executable
 * Generation). CEG executables are signed per account and verify themselves through steamclient
 * at runtime, so neither Steamless nor the emulated Steam client can make them start; they sit on
 * a black screen instead.
 *
 * Switches the container to the real Steam client in headless mode, once per container: a launch
 * mode the user picks afterwards is kept.
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
            if (container.getExtra(APPLIED_EXTRA) == "1") {
                return true
            }

            if (!container.isLaunchRealSteam) {
                container.setLaunchRealSteam(true)
                container.setLaunchBionicSteam(false)
                container.setSteamType(Container.STEAM_TYPE_HEADLESS)
                Timber.tag("GameFixes").i("Enabled headless real Steam client for game $gameId (Steam client required)")
            }
            container.putExtra(APPLIED_EXTRA, "1")
            container.saveData()
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to enable a Steam client for game $gameId")
            false
        }
    }

    private companion object {
        const val APPLIED_EXTRA = "steamClientRequiredFixApplied"
    }
}

class KeyedSteamClientRequiredFix(
    override val gameSource: GameSource,
    override val gameId: String,
) : KeyedGameFix, GameFix by SteamClientRequiredFix()
