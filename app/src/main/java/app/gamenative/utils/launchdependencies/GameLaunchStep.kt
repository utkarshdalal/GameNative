package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container

/**
 * Launch step that runs the game. Always last in the sequence; defines the game command and termination callback.
 */
class GameLaunchStep(
    private val getGameCommand: () -> String,
    private val gameTerminationCallback: (Int) -> Unit,
) : LaunchStep {
    override fun appliesTo(container: Container, appId: String, gameSource: GameSource): Boolean = true

    override fun run(
        context: Context,
        appId: String,
        container: Container,
        stepRunner: StepRunner,
        gameSource: GameSource,
    ): Boolean {
        stepRunner.runStepContent(getGameCommand())
        return true
    }

    override fun terminationCallback(): ((Int) -> Unit)? = gameTerminationCallback
}
