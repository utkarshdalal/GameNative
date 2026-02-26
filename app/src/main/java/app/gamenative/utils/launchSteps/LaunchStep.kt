package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container

/**
 * Receives step content and runs it (builds full guest executable, sets on launcher, starts).
 * Implemented by the launch orchestrator; steps call this instead of building commands themselves.
 */
interface StepRunner {
    fun runStepContent(stepContent: String)
}

/**
 * A single launch step (e.g. VC Redist, GOG scriptinterpreter, game launch).
 * Each step is run once per launch; when it terminates, the next step runs or the game starts.
 */
interface LaunchStep {
    /** Whether this step applies to the given container/app. */
    fun appliesTo(container: Container, appId: String, gameSource: GameSource): Boolean

    /**
     * Run this step. Called only when [appliesTo] is true.
     * If this step has content to run, build it and call [stepRunner.runStepContent]; return true.
     * If this step should be skipped (no content), return false.
     */
    fun run(
        context: Context,
        appId: String,
        container: Container,
        stepRunner: StepRunner,
        gameSource: GameSource,
    ): Boolean

    /**
     * Callback to use when this step's process terminates. Null for install-deps steps (emit-only);
     * non-null for the game step (treat non-zero as error). Default is null.
     */
    fun terminationCallback(): ((Int) -> Unit)? = null
}
