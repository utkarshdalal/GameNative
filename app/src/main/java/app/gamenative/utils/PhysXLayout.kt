package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import java.io.File
import timber.log.Timber

/**
 * NVIDIA PhysX System Software 9.23.1019 plus the 9.13.0604 legacy engines, laid out as the
 * `physx` wincomponent. The archive is rooted at drive_c and the registry values below are
 * the ones the installers write.
 */
object PhysXLayout {
    const val COMPONENT = "physx"
    const val INSTALL_DIR = "C:\\Program Files (x86)\\NVIDIA Corporation\\PhysX"
    private const val ENGINE_DIR = "$INSTALL_DIR\\Engine"

    private val INSTALLER_EXTENSIONS = setOf("exe", "msi")
    private val REGISTRY_ROOTS = listOf("Software", "Software\\Wow6432Node")

    data class RegValue(val key: String, val name: String?, val string: String? = null, val dword: Int? = null)

    private val A32_ENGINES = linkedMapOf(
        "2.3.1" to 2, "2.3.2" to 5, "2.3.3" to 4, "2.4.0" to 3, "2.4.1" to 4, "2.4.4" to 4,
        "2.5.0" to 16, "2.5.1" to 5, "2.5.3" to 2, "2.5.4" to 2, "2.6.0" to 5, "2.6.1" to 6,
        "2.6.2" to 6, "2.6.3" to 6, "2.6.4" to 5, "2.7.0" to 9, "2.7.1" to 43, "2.7.2" to 9,
        "2.7.3" to 53, "2.7.4" to 33, "2.7.5" to 40, "2.7.6" to 33, "2.8.0" to 42, "2.8.1" to 54,
        "2.8.3" to 43,
    )
    private val A64_ENGINES = linkedMapOf("2.8.3" to 44)

    private val DLL_TABLE = listOf(
        Triple("1F0D3CC8-ADDF-49b3-87B3-F232A762B0C8", "dll_path", "F232A762B0C8\\PhysXCore.dll"),
        Triple("26097F37-C68B-4fc0-AA37-6FE164B5308A", "dll_path", "6FE164B5308A\\PhysXCore.dll"),
        Triple("3F527913-C421-4c9b-BDBA-C22346819C37", "dll_path", "C22346819C37\\PhysXCore.dll"),
        Triple("56C8B958-C577-4BC6-AEB7-4F8D3BB436C7", "dll_base_directory", "4F8D3BB436C7\\"),
        Triple("5A9F1682-9FE0-4c7f-A50E-5182B3C9EFEC", "dll_path", "5182B3C9EFEC\\PhysXCore.dll"),
        Triple("62849F71-B304-487f-BA78-82D8704BAB07", "dll_path", "82D8704BAB07\\PhysXCore.dll"),
        Triple("974EB307-2545-477A-90DA-07022E82A068", "dll_path", "07022E82A068\\GRB_1_x86.dll"),
        Triple("B1BD90FB-BC49-428a-8FF1-549B931E903E", "dll_path", "549B931E903E\\PhysXCore.dll"),
        Triple("B7525822-D374-43c2-8843-B1E4CC1EB042", "dll_path", "B1E4CC1EB042\\PhysXCore.dll"),
        Triple("7E3AE57C-FA76-4bd3-9CC6-A0A1369354E1", "dll_base_directory", "A0A1369354E1\\"),
        Triple("844243E3-18C6-4CF2-86FB-73BE2921551D", "dll_base_directory", "73BE2921551D\\"),
        Triple("351C9090-206D-4915-AF80-86C5F4F22ECD", "dll_base_directory", "86C5F4F22ECD\\"),
    )

    private fun ageiaKey(root: String) = "$root\\AGEIA Technologies"
    private fun systemSoftwareKey(root: String) = "$root\\NVIDIA Corporation\\PhysX_SystemSoftware"
    private fun updateLoaderKey(root: String) = "$root\\NVIDIA Corporation\\PhysXUpdateLoader"

    fun registryValues(): List<RegValue> = REGISTRY_ROOTS.flatMap { root ->
        val ageia = ageiaKey(root)
        buildList {
            add(RegValue(ageia, "HwSelection", string = "GPU"))
            add(RegValue(ageia, "PhysXCore Path", string = ENGINE_DIR))
            add(RegValue(ageia, "PhysX Version", dword = 9231019))
            add(RegValue(ageia, "PhysX (GPU) Version", dword = 9231019))
            add(RegValue(ageia, "PhysX (Legacy) Version", dword = 9130604))
            add(RegValue(ageia, "PhysX BuildCL", dword = 0))
            add(RegValue("$ageia\\EnableQuietMode", null, string = ""))
            A32_ENGINES.forEach { (version, build) -> add(RegValue("$ageia\\PhysX_A32_Engines", version, dword = build)) }
            A64_ENGINES.forEach { (version, build) -> add(RegValue("$ageia\\PhysX_A64_Engines", version, dword = build)) }
            val systemSoftware = systemSoftwareKey(root)
            add(RegValue(systemSoftware, "PhysX (GPU) Version", string = "9.23.1019"))
            add(RegValue(systemSoftware, "PhysX (Legacy) Version", string = "9.13.0604"))
            add(RegValue(systemSoftware, "PhysXCore Path", string = ENGINE_DIR))
            DLL_TABLE.forEach { (guid, name, path) ->
                add(RegValue("${updateLoaderKey(root)}\\DllTable\\$guid", name, string = "$ENGINE_DIR\\$path"))
            }
        }
    }

    @JvmStatic
    fun applyRegistry(systemRegFile: File, enabled: Boolean) {
        WineRegistryEditor(systemRegFile).use { editor ->
            editor.setCreateKeyIfNotExist(true)
            if (enabled) {
                for (value in registryValues()) {
                    if (value.dword != null) {
                        editor.setDwordValue(value.key, value.name, value.dword)
                    } else {
                        editor.setStringValue(value.key, value.name, value.string)
                    }
                }
            } else {
                for (root in REGISTRY_ROOTS) {
                    editor.removeKey(ageiaKey(root), true)
                    editor.removeKey(systemSoftwareKey(root), true)
                    editor.removeKey(updateLoaderKey(root), true)
                }
            }
        }
    }

    internal fun isInstaller(file: File): Boolean {
        if (!file.isFile || file.extension.lowercase() !in INSTALLER_EXTENSIONS) return false
        if (file.name.startsWith("physx", ignoreCase = true)) return true
        val strings = PeVersionReader.read(file)?.strings ?: return false
        return listOfNotNull(strings["ProductName"], strings["FileDescription"]).any { it.contains("PhysX", ignoreCase = true) }
    }

    internal fun layoutInstallers(gameDir: File): List<File> {
        val physx = RedistPaths.child(gameDir, "_CommonRedist")?.let { RedistPaths.child(it, "PhysX") }
            ?.takeIf { it.isDirectory }
            ?: return emptyList()
        val entries = physx.listFiles().orEmpty().sortedBy { it.name.lowercase() }
        return entries.filter { it.isFile } +
            entries.filter { it.isDirectory }.flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name.lowercase() } }
    }

    internal fun candidateInstallers(source: GameSource?, gameDir: File, installScript: String?): List<File> {
        val fromSource = when (source) {
            null -> emptyList()
            GameSource.STEAM -> RedistInstallerExtractor.scriptStepInstallers(gameDir, installScript)
            else -> RedistComponents.planFor(source, gameDir)?.installers.orEmpty() +
                RedistInstallerExtractor.unrealLayoutInstallers(gameDir)
        }
        return RedistInstallerExtractor.expandInstallers(fromSource + layoutInstallers(gameDir))
    }

    fun hasInstaller(installers: List<File>): Boolean =
        installers.firstOrNull(::isInstaller)?.also { Timber.d("PhysX installer: ${it.absolutePath}") } != null

    fun applyForLaunch(container: Container) {
        try {
            val gameDir = PreInstallSteps.getGameDir(container)?.takeIf { it.isDirectory } ?: return
            val source = runCatching { ContainerUtils.extractGameSourceFromContainerId(container.id) }.getOrNull()
            val installScript = if (source == GameSource.STEAM) {
                runCatching { SteamService.getAppInfoOf(ContainerUtils.extractGameIdFromContainerId(container.id))?.installScript }.getOrNull()
            } else {
                null
            }
            if (!hasInstaller(candidateInstallers(source, gameDir, installScript))) return
            val current = container.winComponents.orEmpty()
            val merged = SteamRedistDepots.mergeComponents(current, setOf(COMPONENT))
            if (merged == current) return
            Timber.i("PhysX redistributable found in ${gameDir.absolutePath}, enabling wincomponents: $merged")
            container.winComponents = merged
            container.saveData()
        } catch (e: Exception) {
            Timber.e(e, "Failed to enable PhysX for ${container.id}")
        }
    }
}
