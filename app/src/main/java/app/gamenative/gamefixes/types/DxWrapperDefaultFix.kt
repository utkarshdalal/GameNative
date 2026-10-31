package app.gamenative.gamefixes

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.KeyValueSet
import timber.log.Timber

/**
 * Picks the Direct3D wrapper (and optional wrapper config values) a game is known to need. Applied
 * once per container for a given default: a wrapper the user chooses afterwards is kept, while a
 * changed default (a different [dxwrapper] or [configOverrides]) is applied once more.
 */
class DxWrapperDefaultFix(
    private val dxwrapper: String,
    private val configOverrides: Map<String, String> = emptyMap(),
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        return try {
            val signature = (listOf(dxwrapper) + configOverrides.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" })
                .joinToString(";")
            if (container.getExtra(APPLIED_EXTRA) == signature) {
                return true
            }
            if (!container.dxWrapper.equals(dxwrapper, ignoreCase = true)) {
                container.dxWrapper = dxwrapper
            }
            if (configOverrides.isNotEmpty()) {
                val config = KeyValueSet(container.dxWrapperConfig)
                for ((key, value) in configOverrides) config.put(key, value)
                container.dxWrapperConfig = config.toString()
            }
            Timber.tag("GameFixes").i("Set dxwrapper '$dxwrapper' $configOverrides for game $gameId")
            container.putExtra(APPLIED_EXTRA, signature)
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
