package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.service.gog.GOGService
import com.winlator.container.Container

/** Pre-launch step that runs GOG scriptinterpreter.exe when required by the game manifest. */
object GogScriptInterpreterPreLaunchStep : LaunchStep {
    override fun appliesTo(container: Container, appId: String, gameSource: GameSource): Boolean =
        gameSource == GameSource.GOG

    override fun run(
        context: Context,
        appId: String,
        container: Container,
        stepRunner: StepRunner,
        gameSource: GameSource,
    ): Boolean {
        val parts = GOGService.getInstance()?.gogManager?.getScriptInterpreterPartsForLaunch(appId) ?: return false
        val content = if (parts.isEmpty()) null else parts.joinToString(" & ")
        if (content.isNullOrBlank()) return false
        stepRunner.runStepContent(content)
        return true
    }
}
