package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.utils.installscript.InstallScriptExecutor
import com.winlator.container.Container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber

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

        if (gameSource == GameSource.STEAM) {
            chain += collectInstallScriptCommands(container, appId, screenInfo)
        }

        return chain
    }

    private fun collectInstallScriptCommands(
        container: Container,
        appId: String,
        screenInfo: String,
    ): List<ChainedCommand> {
        return try {
            val numericGameId = ContainerUtils.extractGameIdFromContainerId(appId)
            val steamApp = SteamService.getAppInfoOf(numericGameId) ?: return emptyList()
            val appInfo = runBlocking(Dispatchers.IO) {
                SteamService.instance?.appInfoDao?.get(numericGameId)
            } ?: return emptyList()
            val gameDir = PreInstallSteps.getGameDir(container) ?: return emptyList()

            val scripts = InstallScriptExecutor.collectScripts(
                steamApp = steamApp,
                appInfo = appInfo,
                gameDir = gameDir,
                installDir = "A:",
                language = container.language,
                appId = numericGameId,
            )
            if (scripts.isEmpty()) return emptyList()

            Timber.tag("InstallScript").i("Applying registry keys from ${scripts.size} install script(s)")
            InstallScriptExecutor.applyRegistryKeys(container, scripts, container.language)

            InstallScriptExecutor.getRunProcessCommands(
                container = container,
                scripts = scripts,
                screenInfo = screenInfo,
                is64Bit = container.isWoW64Mode,
            ).map { cmd ->
                ChainedCommand(cmd.executable) {
                    if (cmd.hasRunKey != null) {
                        val exitCode = InstallScriptExecutor.readExitCode(container)
                        if (exitCode == 0) {
                            InstallScriptExecutor.markRunProcessComplete(container, cmd.hasRunKey)
                        } else {
                            Timber.tag("InstallScript").w(
                                "Run process exited with code $exitCode, will retry next launch",
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("InstallScript").w(e, "InstallScript execution failed")
            emptyList()
        }
    }
}
