package app.gamenative.gamefixes

import android.content.Context
import com.winlator.container.Container
import com.winlator.inputcontrols.InputControlsManager
import timber.log.Timber

/**
 * Selects a bundled control profile for a game, once per container: a profile the user picks
 * afterwards is kept. Looked up by name because a bundled profile's id can change on install when
 * the user already has a profile with that id.
 */
class ControlProfileDefaultFix(
    private val profileName: String,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        return try {
            if (container.getExtra(APPLIED_EXTRA) == profileName) {
                return true
            }
            val profile = InputControlsManager(context).getProfiles(false)
                .firstOrNull { it.name.equals(profileName, ignoreCase = true) }
            if (profile == null) {
                Timber.tag("GameFixes").w("Control profile '$profileName' not found for game $gameId")
                return false
            }
            container.putExtra("profileId", profile.id.toString())
            container.putExtra(APPLIED_EXTRA, profileName)
            container.saveData()
            Timber.tag("GameFixes").i("Selected control profile '$profileName' (id ${profile.id}) for game $gameId")
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to select control profile '$profileName' for game $gameId")
            false
        }
    }

    private companion object {
        const val APPLIED_EXTRA = "controlProfileDefaultFixApplied"
    }
}
