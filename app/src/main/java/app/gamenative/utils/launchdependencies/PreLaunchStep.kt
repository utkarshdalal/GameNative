package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.xenvironment.components.GuestProgramLauncherComponent

/**
 * A single pre-launch step (e.g. GOG scriptinterpreter, game-specific setup).
 * Steps are run in preUnpack when the environment is ready, before the game executable runs.
 */
interface PreLaunchStep {
    /** Whether this step applies to the given container/app. */
    fun appliesTo(container: Container, appId: String, gameSource: GameSource): Boolean

    /**
     * Run this step. Called only when [appliesTo] is true.
     * Exceptions should be caught by the registry so one failing step does not block others.
     */
    fun run(
        context: Context,
        appId: String,
        container: Container,
        guestProgramLauncherComponent: GuestProgramLauncherComponent,
        gameSource: GameSource,
    )
}
