package app.gamenative.utils

import app.gamenative.data.GameSource
import com.winlator.container.Container

object PreLaunchSetup {

    data class ChainedCommand(
        val executable: String,
        val onComplete: () -> Unit,
    )

    fun buildChain(
        container: Container,
        appId: String,
        gameSource: GameSource,
        screenInfo: String,
        containerVariantChanged: Boolean,
    ): List<ChainedCommand> {
        val chain = mutableListOf<ChainedCommand>()

        val preInstallCmds = PreInstallSteps.getPreInstallCommands(
            container = container,
            appId = appId,
            gameSource = gameSource,
            screenInfo = screenInfo,
            containerVariantChanged = containerVariantChanged,
        )
        chain += preInstallCmds.map { cmd ->
            ChainedCommand(cmd.executable) {
                PreInstallSteps.markStepDone(container, cmd.marker)
            }
        }

        return chain
    }
}
