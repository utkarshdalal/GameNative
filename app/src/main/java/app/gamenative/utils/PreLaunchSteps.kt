package app.gamenative.utils

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.utils.launchdependencies.GogScriptInterpreterPreLaunchStep
import app.gamenative.utils.launchdependencies.PreLaunchStep
import app.gamenative.utils.launchdependencies.VcRedistPreLaunchStep
import com.winlator.container.Container

/**
 * Registry for all pre-launch steps.
 * Each applicable step contributes fragments to a single `cmd /c` chain run in the main Wine session.
 * A short delay is inserted between each fragment so installer children (e.g. msiexec) can finish.
 */
class PreLaunchSteps {
    companion object {
        private val preLaunchSteps: List<PreLaunchStep> = listOf(
            VcRedistPreLaunchStep,
            GogScriptInterpreterPreLaunchStep,
        )
    }

    /**
     * Builds the pre-launch chain string by collecting [PreLaunchStep.getChainFragments] from each
     * applicable step and joining with " & " between each fragment. Returns empty if none.
     */
    fun buildChain(
        context: Context,
        appId: String,
        container: Container,
        gameSource: GameSource,
    ): String {
        val parts = mutableListOf<String>()
        for (step in preLaunchSteps) {
            if (!step.appliesTo(container, appId, gameSource)) continue
            for (fragment in step.getChainFragments(context, appId, container, gameSource)) {
                val trimmed = fragment.trim()
                if (trimmed.isNotEmpty()) parts.add(trimmed)
            }
        }
        return if (parts.isEmpty()) "" else parts.joinToString(" & ") + " & "
    }
}
