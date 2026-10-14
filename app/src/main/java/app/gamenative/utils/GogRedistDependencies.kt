package app.gamenative.utils

import java.io.File

object GogRedistDependencies {
    private val MSVC = Regex("^MSVC(\\d{4})(_X64|_X86)?$")

    fun componentsFor(dependencyId: String): Set<String> {
        val id = dependencyId.trim().uppercase()
        MSVC.matchEntire(id)?.let { match ->
            return setOfNotNull(VcRedistYears.component(match.groupValues[1].toInt()))
        }
        return when {
            id == "DIRECTX" || id == "DX9" || id.startsWith("DIRECTX") || id.startsWith("D3DX") ->
                setOf("direct3d", "xaudio")
            id.startsWith("OPENAL") -> setOf("openal")
            else -> emptySet()
        }
    }

    fun map(dependencyIds: Collection<String>): RedistMapping {
        val components = linkedSetOf<String>()
        val unmapped = mutableListOf<String>()
        for (id in dependencyIds) {
            val mapped = componentsFor(id)
            if (mapped.isEmpty()) unmapped.add(id) else components.addAll(mapped)
        }
        return RedistMapping(components, unmapped)
    }

    fun installersFor(gameDir: File, dependencyId: String): List<File> {
        val dir = RedistPaths.resolve(gameDir, "_CommonRedist/${dependencyId.trim()}")?.takeIf { it.isDirectory }
            ?: return emptyList()
        return RedistPaths.installersIn(dir)
    }

    fun plan(gameDir: File, dependencyIds: Collection<String>): RedistPlan {
        val mapping = map(dependencyIds)
        val installers = dependencyIds
            .filter { componentsFor(it).isNotEmpty() }
            .flatMap { installersFor(gameDir, it) }
        return RedistPlan(mapping.components, installers, mapping.unmapped)
    }
}
