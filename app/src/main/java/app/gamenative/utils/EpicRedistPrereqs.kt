package app.gamenative.utils

import java.io.File

object EpicRedistPrereqs {
    private val UNREAL_SETUP = listOf("ue4prereqsetup", "ueprereqsetup")

    fun componentsFor(prereqName: String, prereqPath: String): Set<String> {
        val name = prereqName.lowercase()
        val path = prereqPath.replace('\\', '/').lowercase()
        val text = "$name $path"
        val isUnreal = UNREAL_SETUP.any { it in text } ||
            ("unreal engine" in name && "prerequisite" in name)
        return when {
            isUnreal -> setOf("vcrun2022", "direct3d")
            "vcredist" in path || "vc_redist" in path || "visual c++" in name ->
                setOf(VcRedistYears.findIn(text) ?: "vcrun2022")
            "dxsetup" in path || "directx" in text -> setOf("direct3d", "xaudio")
            else -> emptySet()
        }
    }

    fun map(prereqIds: Collection<String>, prereqName: String, prereqPath: String): RedistMapping {
        if (prereqName.isBlank() && prereqPath.isBlank()) return RedistMapping(emptySet(), prereqIds.filter { it.isNotBlank() })
        val components = componentsFor(prereqName, prereqPath)
        val unmapped = if (components.isEmpty()) listOf(prereqName.ifBlank { prereqPath }) else emptyList()
        return RedistMapping(components, unmapped)
    }

    fun installerFor(gameDir: File, prereqPath: String): File? {
        if (prereqPath.isBlank()) return null
        return RedistPaths.resolve(gameDir, prereqPath)?.takeIf { it.isFile }
    }

    fun plan(gameDir: File, prereqIds: Collection<String>, prereqName: String, prereqPath: String): RedistPlan {
        val mapping = map(prereqIds, prereqName, prereqPath)
        val installers = if (mapping.components.isEmpty()) emptyList() else listOfNotNull(installerFor(gameDir, prereqPath))
        return RedistPlan(mapping.components, installers, mapping.unmapped)
    }
}
