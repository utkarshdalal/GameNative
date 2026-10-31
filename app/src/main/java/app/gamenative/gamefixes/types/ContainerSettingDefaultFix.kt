package app.gamenative.gamefixes

import android.content.Context
import com.winlator.container.Container
import timber.log.Timber

/**
 * Sets one container setting to the value a game is known to need, once per container: a value the
 * user chooses afterwards is kept, while a changed default is applied once more.
 */
class ContainerSettingDefaultFix(
    private val setting: String,
    private val value: String,
    private val read: (Container) -> String?,
    private val write: (Container, String) -> Unit,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        val appliedExtra = "${setting}DefaultFixApplied"
        return try {
            if (container.getExtra(appliedExtra) == value) {
                return true
            }
            if (!read(container).equals(value, ignoreCase = true)) {
                write(container, value)
                Timber.tag("GameFixes").i("Set $setting '$value' for game $gameId")
            }
            container.putExtra(appliedExtra, value)
            container.saveData()
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to set $setting '$value' for game $gameId")
            false
        }
    }

    companion object {
        fun displayServer(value: String) = ContainerSettingDefaultFix(
            "displayServer", value, { it.displayServer }, { c, v -> c.displayServer = v },
        )

        fun audioDriver(value: String) = ContainerSettingDefaultFix(
            "audioDriver", value, { it.audioDriver }, { c, v -> c.audioDriver = v },
        )
    }
}
