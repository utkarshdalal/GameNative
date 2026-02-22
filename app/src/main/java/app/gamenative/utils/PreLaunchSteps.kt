package app.gamenative.utils

import android.content.Context
import app.gamenative.utils.launchdependencies.GogScriptInterpreterPreLaunchStep
import app.gamenative.utils.launchdependencies.PreLaunchStep
import com.winlator.container.Container
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import timber.log.Timber

private val ALL_PRE_LAUNCH_STEPS: List<PreLaunchStep> = listOf(
    GogScriptInterpreterPreLaunchStep,
)

/**
 * Runs all pre-launch steps that apply to this container/app.
 * Each step is run in order; exceptions are caught and logged per step so one failure does not block others.
 */
fun runPreLaunchSteps(
    context: Context,
    appId: String,
    container: Container,
    guestProgramLauncherComponent: GuestProgramLauncherComponent,
) {
    for (step in ALL_PRE_LAUNCH_STEPS) {
        if (!step.appliesTo(container, appId)) continue
        try {
            step.run(context, appId, container, guestProgramLauncherComponent)
        } catch (e: Exception) {
            Timber.tag("PreLaunchSteps").e(e, "Pre-launch step failed: ${step::class.simpleName}")
        }
    }
}
