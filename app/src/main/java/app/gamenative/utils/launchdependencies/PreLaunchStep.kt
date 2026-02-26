package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container

/**
 * A single pre-launch step (e.g. VC Redist, GOG scriptinterpreter).
 * Each step contributes fragments to a single `cmd /c` chain run in the main Wine session.
 */
interface PreLaunchStep {
    /** Whether this step applies to the given container/app. */
    fun appliesTo(container: Container, appId: String, gameSource: GameSource): Boolean

    /**
     * Fragments to append to the pre-launch `cmd /c` chain (e.g. one command per element).
     * Only called when [appliesTo] is true. Return empty list if nothing to run.
     * [PreLaunchSteps] joins all fragments from all steps with " & ".
     */
    fun getChainFragments(
        context: Context,
        appId: String,
        container: Container,
        gameSource: GameSource,
    ): List<String>
}
