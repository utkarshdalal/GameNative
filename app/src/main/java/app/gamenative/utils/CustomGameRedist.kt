package app.gamenative.utils

import java.io.File

object CustomGameRedist {
    private const val INSTALL_SCRIPT = "installscript.vdf"
    private const val INSTALL_SCRIPT_DEPTH = 3
    private val MSVC_DIR = Regex("^MSVC(\\d{4})(_X64|_X86)?$", RegexOption.IGNORE_CASE)
    private val COMMON_REDIST_VC = listOf("VC_redist.x86.exe", "VC_redist.x64.exe")
    private val REDIST_VC = listOf("vcredist_x86.exe", "vcredist_x64.exe")

    fun hasSteamInstallScript(gameDir: File): Boolean {
        if (RedistPaths.child(gameDir, INSTALL_SCRIPT)?.isFile == true) return true
        val commonRedist = RedistPaths.child(gameDir, "_CommonRedist")?.takeIf { it.isDirectory } ?: return false
        return commonRedist.walkTopDown()
            .maxDepth(INSTALL_SCRIPT_DEPTH)
            .any { it.isFile && it.name.equals(INSTALL_SCRIPT, ignoreCase = true) }
    }

    fun resolve(gameDir: File): RedistPlan {
        if (hasSteamInstallScript(gameDir)) return RedistPlan.EMPTY
        val components = linkedSetOf<String>()
        val installers = mutableListOf<File>()
        val commonRedist = RedistPaths.child(gameDir, "_CommonRedist")?.takeIf { it.isDirectory }

        commonRedist?.let { RedistPaths.child(it, "vcredist") }?.takeIf { it.isDirectory }
            ?.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
            ?.forEach { yearDir ->
                val component = yearDir.name.toIntOrNull()?.let(VcRedistYears::component) ?: return@forEach
                val found = RedistPaths.installersIn(yearDir).filter { isVcRedist(it.name) }
                if (found.isEmpty()) return@forEach
                components.add(component)
                installers.addAll(found)
            }

        commonRedist?.let { dir ->
            COMMON_REDIST_VC.mapNotNull { name -> RedistPaths.child(dir, name)?.takeIf { it.isFile } }
                .takeIf { it.isNotEmpty() }
                ?.let {
                    components.add("vcrun2022")
                    installers.addAll(it)
                }
        }

        commonRedist?.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { msvcDir ->
            val year = MSVC_DIR.matchEntire(msvcDir.name)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            val component = VcRedistYears.component(year) ?: return@forEach
            val found = RedistPaths.installersIn(msvcDir).filter { isMsvcRedist(it.name) }
            if (found.isEmpty()) return@forEach
            components.add(component)
            installers.addAll(found)
        }

        RedistPaths.child(gameDir, "redist")?.takeIf { it.isDirectory }?.let { dir ->
            REDIST_VC.mapNotNull { name -> RedistPaths.child(dir, name)?.takeIf { it.isFile } }.forEach { exe ->
                VcRedistYears.findIn("redist/${exe.name}")?.let { components.add(it) }
                installers.add(exe)
            }
        }

        commonRedist?.let { RedistPaths.child(it, "DirectX") }?.takeIf { it.isDirectory }?.let { dxDir ->
            components.add("direct3d")
            components.add("xaudio")
            installers.addAll(findOneLevel(dxDir, "dxsetup.exe"))
        }

        val openAl = commonRedist?.let { RedistPaths.child(it, "OpenAL") }?.takeIf { it.isDirectory }
            ?.let { findOneLevel(it, "oalinst.exe") }.orEmpty()
            .ifEmpty { listOfNotNull(RedistPaths.resolve(gameDir, "redist/oalinst.exe")?.takeIf { it.isFile }) }
        if (openAl.isNotEmpty()) {
            components.add(RedistComponents.OPENAL)
            installers.addAll(openAl)
        }

        return RedistPlan(components, installers)
    }

    private fun isVcRedist(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".exe") && (lower.startsWith("vc_redist.") || lower.startsWith("vcredist_"))
    }

    private fun isMsvcRedist(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".exe") && lower.startsWith("vc") && "redist" in lower
    }

    private fun findOneLevel(dir: File, fileName: String): List<File> {
        RedistPaths.child(dir, fileName)?.takeIf { it.isFile }?.let { return listOf(it) }
        return dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
            ?.mapNotNull { sub -> RedistPaths.child(sub, fileName)?.takeIf { it.isFile } }
            .orEmpty()
    }
}
