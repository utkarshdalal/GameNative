package app.gamenative.gamefixes

import android.content.Context
import com.winlator.container.Container
import timber.log.Timber

/**
 * Picks the Direct3D wrapper a game is known to need, once per container: a wrapper the user
 * chooses afterwards is kept.
 */
class DxWrapperDefaultFix(
    private val dxwrapper: String,
) : GameFix {
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
            if (!container.dxWrapper.equals(dxwrapper, ignoreCase = true)) {
                container.dxWrapper = dxwrapper
                Timber.tag("GameFixes").i("Set dxwrapper '$dxwrapper' for game $gameId")
            }
            container.putExtra(APPLIED_EXTRA, "1")
            container.saveData()
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to set dxwrapper '$dxwrapper' for game $gameId")
            false
        }
    }

    private companion object {
        const val APPLIED_EXTRA = "dxWrapperDefaultFixApplied"
    }
}
