package app.gamenative.utils

import app.gamenative.data.DepotInfo
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

/**
 * Maps the Steamworks Shared (app 228980) redistributable depots an app lists in PICS
 * to the wincomponents that provide the same runtime.
 */
object SteamRedistDepots {
    const val STEAMWORKS_SHARED_APP_ID = 228980

    val DEPOT_COMPONENTS: Map<Int, Set<String>> = mapOf(
        228981 to setOf("vcrun2005"), // VC++ 2005
        228982 to setOf("vcrun2008"), // VC++ 2008
        228983 to setOf("vcrun2010"), // VC++ 2010
        228984 to setOf("vcrun2012"), // VC++ 2012
        228985 to setOf("vcrun2013"), // VC++ 2013
        228986 to setOf("vcrun2022"), // VC++ 2015
        228987 to setOf("vcrun2022"), // VC++ 2017
        228988 to setOf("vcrun2022"), // VC++ 2019
        228989 to setOf("vcrun2022"), // VC++ 2022
        228990 to setOf("direct3d", "xaudio"), // DirectX June 2010
    )

    fun componentsForDepots(depots: Collection<DepotInfo>): Set<String> =
        depots
            .filter { it.depotFromApp == STEAMWORKS_SHARED_APP_ID && it.sharedInstall }
            .flatMapTo(linkedSetOf()) { DEPOT_COMPONENTS[it.depotId].orEmpty() }

    fun componentsForApp(appId: Int): Set<String> {
        val app = SteamService.getAppInfoOf(appId) ?: return emptySet()
        return componentsForDepots(app.depots.values)
    }

    fun mergeComponents(winComponents: String, required: Set<String>): String {
        val entries = winComponents.split(",").filter { it.isNotBlank() }
        val present = entries.mapTo(hashSetOf()) { it.substringBefore("=") }
        val added = required.filter { it !in present }.map { "$it=1" }
        if (added.isEmpty()) return winComponents
        return (entries + added).joinToString(",")
    }

    internal fun scriptComponents(gameDir: File?, scriptName: String?): Set<String> {
        if (gameDir == null || !gameDir.isDirectory) return emptySet()
        return RedistInstallerExtractor.familiesOf(RedistInstallerExtractor.scriptStepInstallers(gameDir, scriptName))
    }

    fun applyForLaunch(container: Container, appId: String) {
        try {
            if (ContainerUtils.extractGameSourceFromContainerId(appId) != GameSource.STEAM) return
            val numericAppId = ContainerUtils.extractGameIdFromContainerId(appId)
            val fromScripts = try {
                scriptComponents(PreInstallSteps.getGameDir(container), SteamService.getAppInfoOf(numericAppId)?.installScript)
            } catch (e: Exception) {
                Timber.w(e, "Failed to read redist installers named by install scripts for $appId")
                emptySet()
            }
            val required = componentsForApp(numericAppId) + fromScripts
            if (required.isEmpty()) return
            val current = container.winComponents.orEmpty()
            val merged = mergeComponents(current, required)
            if (merged == current) return
            Timber.i("Steam shared depots and install scripts for $appId enable wincomponents: $merged")
            container.winComponents = merged
            container.saveData()
        } catch (e: Exception) {
            Timber.e(e, "Failed to merge Steam redist depot components for $appId")
        }
    }
}
