package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.service.epic.EpicInstallState
import app.gamenative.service.gog.GOGManifestUtils
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import timber.log.Timber

data class RedistMapping(
    val components: Set<String>,
    val unmapped: List<String>,
)

data class RedistPlan(
    val components: Set<String>,
    val installers: List<File>,
    val unmapped: List<String> = emptyList(),
) {
    fun installersExtra(): String = installers.map { it.absolutePath }.distinct().joinToString("\n")

    companion object {
        val EMPTY = RedistPlan(emptySet(), emptyList())
    }
}

object RedistPaths {
    fun child(dir: File, name: String): File? {
        val entries = dir.listFiles() ?: return null
        return entries.firstOrNull { it.name == name } ?: entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    fun resolve(root: File, relativePath: String): File? {
        val segments = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || ".." in segments) return null
        var current = root
        for (segment in segments) {
            current = child(current, segment) ?: return null
        }
        return current
    }

    fun installersIn(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".exe", ignoreCase = true) || it.name.endsWith(".msi", ignoreCase = true)) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
}

object VcRedistYears {
    private val YEAR = Regex("(?<!\\d)(2005|2008|2010|2012|2013|2015|2017|2019|2022)(?!\\d)")
    private val VC_TOOLSET = Regex("(?<![a-z])vc(80|90|100|110|120|14\\d)(?!\\d)")
    private val VERSION = Regex("(?<![\\d.])(8|9|10|11|12|14)\\.\\d+(?:\\.\\d+)*")

    fun component(year: Int): String? = when (year) {
        2005, 2008, 2010, 2012, 2013 -> "vcrun$year"
        2015, 2017, 2019, 2022 -> "vcrun2022"
        else -> null
    }

    private fun yearForMajor(major: Int): Int? = when (major) {
        8 -> 2005
        9 -> 2008
        10 -> 2010
        11 -> 2012
        12 -> 2013
        14 -> 2022
        else -> null
    }

    fun findIn(text: String): String? {
        val lower = text.lowercase()
        YEAR.find(lower)?.let { return component(it.groupValues[1].toInt()) }
        VC_TOOLSET.find(lower)?.let { match ->
            val toolset = match.groupValues[1]
            val major = if (toolset.startsWith("14")) 14 else toolset.dropLast(1).toInt()
            return yearForMajor(major)?.let(::component)
        }
        VERSION.find(lower)?.let { return yearForMajor(it.groupValues[1].toInt())?.let(::component) }
        return null
    }
}

object RedistComponents {
    const val OPENAL = "openal"
    const val INSTALLERS_EXTRA = "redist_installers"

    fun applyForLaunch(container: Container, appId: String) {
        val source = ContainerUtils.extractGameSourceFromContainerId(appId)
        if (source == GameSource.STEAM) {
            SteamRedistDepots.applyForLaunch(container, appId)
            return
        }
        try {
            val gameDir = PreInstallSteps.getGameDir(container)?.takeIf { it.isDirectory } ?: return
            val plan = planFor(source, gameDir) ?: return
            if (plan.unmapped.isNotEmpty()) {
                Timber.i("Redist dependencies for $appId without a wincomponent: ${plan.unmapped.joinToString()}")
            }
            var changed = false
            if (OPENAL in plan.components) {
                val envVars = EnvVars(container.envVars)
                val currentOverrides = envVars.get("WINEDLLOVERRIDES")
                val mergedOverrides = OpenALRedist.mergeDllOverride(currentOverrides)
                if (mergedOverrides != currentOverrides) {
                    envVars.put("WINEDLLOVERRIDES", mergedOverrides)
                    container.envVars = envVars.toString()
                    changed = true
                }
            }
            val installerFamilies = try {
                RedistInstallerExtractor.familiesOf(RedistInstallerExtractor.expandInstallers(plan.installers))
            } catch (e: Exception) {
                Timber.w(e, "Failed to read redist installer versions for $appId")
                emptySet()
            }
            val current = container.winComponents.orEmpty()
            val merged = SteamRedistDepots.mergeComponents(current, plan.components - OPENAL + installerFamilies)
            if (merged != current) {
                container.winComponents = merged
                changed = true
            }
            val installers = plan.installersExtra()
            if (container.getExtra(INSTALLERS_EXTRA) != installers) {
                container.putExtra(INSTALLERS_EXTRA, installers)
                changed = true
            }
            if (!changed) return
            Timber.i("Redist metadata for $appId enables wincomponents: $merged, installers: ${plan.installers.size}")
            container.saveData()
        } catch (e: Exception) {
            Timber.e(e, "Failed to merge redist components for $appId")
        }
    }

    fun planFor(source: GameSource, gameDir: File): RedistPlan? {
        val plan = when (source) {
            GameSource.GOG -> gogPlan(gameDir)
            GameSource.EPIC -> epicPlan(gameDir)
            GameSource.CUSTOM_GAME -> CustomGameRedist.resolve(gameDir)
            else -> return null
        }
        if (plan.installers.isNotEmpty()) return plan
        val unreal = RedistInstallerExtractor.unrealLayoutInstallers(gameDir)
        if (unreal.isEmpty()) return plan
        Timber.i("No store redist metadata, using Unreal redistributables: $unreal")
        return plan.copy(installers = unreal)
    }

    private fun gogPlan(gameDir: File): RedistPlan {
        val ids = GOGManifestUtils.readDependencies(gameDir)
            ?: RedistPaths.child(gameDir, "_CommonRedist")?.listFiles()?.filter { it.isDirectory }?.map { it.name }
            ?: return RedistPlan.EMPTY
        return GogRedistDependencies.plan(gameDir, ids)
    }

    private fun epicPlan(gameDir: File): RedistPlan {
        val state = EpicInstallState.read(gameDir.absolutePath) ?: return RedistPlan.EMPTY
        return EpicRedistPrereqs.plan(gameDir, state.prereqIds, state.prereqName, state.prereqPath)
    }
}
