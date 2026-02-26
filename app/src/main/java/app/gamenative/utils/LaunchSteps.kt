package app.gamenative.utils

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.utils.launchdependencies.GogScriptInterpreterPreLaunchStep
import app.gamenative.utils.launchdependencies.LaunchStep
import app.gamenative.utils.launchdependencies.StepRunner
import app.gamenative.utils.launchdependencies.VcRedistPreLaunchStep
import com.winlator.container.Container
import timber.log.Timber

/**
 * Abstraction for the guest launcher used during launch steps and game start.
 */
interface InstallDepsLauncher {
    fun setGuestExecutable(executable: String)
    fun setPreUnpack(block: (() -> Unit)?)
    fun execShellCommand(command: String)
    fun setTerminationCallback(callback: ((Int) -> Unit)?)
    fun start()
}

/**
 * Launch steps (e.g. VC Redist, GOG script) then the game.
 * The game is a [GameLaunchStep] that provides the command and termination callback.
 */
object LaunchSteps {
    private val preSteps: List<LaunchStep> = listOf(
        VcRedistPreLaunchStep,
        GogScriptInterpreterPreLaunchStep,
    )

    /**
     * Starts the launch flow. Configures the launcher for the first applicable step
     * (or the game step) and installs a termination callback that chains subsequent steps.
     *
     * @param gameStep The game step (e.g. [GameLaunchStep]) that runs last and provides the game command and termination callback.
     */
    fun start(
        launcher: InstallDepsLauncher,
        gameStep: LaunchStep,
        context: Context,
        appId: String,
        container: Container,
        gameSource: GameSource,
        screenInfo: String,
        execArgs: String,
    ) {
        val applicablePreSteps = preSteps.filter { it.appliesTo(container, appId, gameSource) }
        val steps = applicablePreSteps + gameStep
        if (steps.isEmpty()) {
            return
        }

        // Track which step is currently running and which remain.
        var pendingSteps: List<LaunchStep> = emptyList()
        var currentStep: LaunchStep? = null

        // Shared termination callback installed on the launcher.
        val terminationHandler: (Int) -> Unit = terminationHandler@ { status ->
            val step = currentStep
            // Invoke step-specific termination callback (e.g. game termination logic).
            step?.terminationCallback()?.invoke(status)

            if (pendingSteps.isEmpty()) {
                // No further steps to run.
                return@terminationHandler
            }

            val next = pendingSteps.first()
            pendingSteps = pendingSteps.drop(1)

            val stepRunner = object : StepRunner {
                override fun runStepContent(stepContent: String) {
                    val executable =
                        if (next.terminationCallback() != null) {
                            stepContent
                        } else {
                            buildStepExecutable(stepContent, screenInfo, execArgs)
                        }
                    runLauncher(launcher, executable)
                    currentStep = next
                }
            }

            if (next.run(context, appId, container, stepRunner, gameSource)) {
                Timber.i("Launch step done; running next (${pendingSteps.size} remaining)")
            } else {
                Timber.i("Launch step skipped; ${pendingSteps.size} remaining")
            }
        }

        launcher.setTerminationCallback(terminationHandler)

        // Find and configure the first step; it will be started when the environment starts
        // the guest launcher component.
        for ((index, step) in steps.withIndex()) {
            val captureRunner = object : StepRunner {
                var builtExecutable: String? = null
                override fun runStepContent(stepContent: String) {
                    builtExecutable =
                        if (step.terminationCallback() != null) {
                            stepContent
                        } else {
                            buildStepExecutable(stepContent, screenInfo, execArgs)
                        }
                }
            }
            if (step.run(context, appId, container, captureRunner, gameSource)) {
                val firstExecutable = captureRunner.builtExecutable ?: continue
                currentStep = step
                pendingSteps = steps.drop(index + 1)
                launcher.setGuestExecutable(firstExecutable)
                return
            }
        }
    }

    private fun buildStepExecutable(stepContent: String, screenInfo: String, execArgs: String): String {
        val cmd = "winhandler.exe cmd /c \"$stepContent & taskkill /F /IM explorer.exe\""
        return "wine explorer /desktop=shell,$screenInfo $cmd" +
            (if (execArgs.isNotEmpty()) " $execArgs" else "")
    }

    private fun runLauncher(launcher: InstallDepsLauncher, executable: String) {
        launcher.setGuestExecutable(executable)
        launcher.setPreUnpack(null)
        try {
            launcher.execShellCommand("wineserver -k")
        } catch (e: Exception) {
            Timber.w(e, "wineserver -k (non-fatal)")
        }
        launcher.start()
    }
}
