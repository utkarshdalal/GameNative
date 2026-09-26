package app.gamenative.utils

import app.gamenative.enums.Marker
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import timber.log.Timber
import java.io.File

/** Windows path -> installer args, checked against host filesystem to see which exist. */
private val vcRedistMap: Map<String, String> = mapOf(
    "A:\\_CommonRedist\\vcredist\\2005\\vcredist_x86.exe" to "/Q",
    "A:\\_CommonRedist\\vcredist\\2005\\vcredist_x64.exe" to "/Q",
    "A:\\_CommonRedist\\vcredist\\2008\\vcredist_x86.exe" to "/qb!",
    "A:\\_CommonRedist\\vcredist\\2008\\vcredist_x64.exe" to "/qb!",
    "A:\\_CommonRedist\\vcredist\\2010\\vcredist_x86.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2010\\vcredist_x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2012\\vcredist_x86.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2012\\vcredist_x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2013\\vcredist_x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2013\\vcredist_x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2015\\vc_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2015\\vc_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2017\\vc_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2017\\vc_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2019\\vc_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2019\\vc_redist.x64.exe" to "/install /passive /norestart",
    "A:\\redist\\vcredist_x86.exe" to "",
    "A:\\redist\\vcredist_x64.exe" to "",
    "A:\\_CommonRedist\\MSVC2005\\vcredist_x86.exe" to "/Q",
    "A:\\_CommonRedist\\MSVC2005_x64\\vcredist_x64.exe" to "/Q",
    "A:\\_CommonRedist\\MSVC2008\\vcredist_x86.exe" to "/qb!",
    "A:\\_CommonRedist\\MSVC2008_x64\\vcredist_x64.exe" to "/qb!",
    "A:\\_CommonRedist\\MSVC2010\\vcredist_x86.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\MSVC2010_x64\\vcredist_x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\MSVC2012\\vcredist_x86.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\MSVC2012_x64\\vcredist_x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\MSVC2013\\vcredist_x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2013_x64\\vcredist_x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2015\\VC_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2015_x64\\VC_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2017\\VC_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2017_x64\\VC_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2019\\VC_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2019_x64\\VC_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\VC_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\VC_redist.x64.exe" to "/install /passive /norestart",
)

private const val DLL_OVERRIDES_KEY = "Software\\Wine\\DllOverrides"
private val v140NativeDlls = listOf(
    "concrt140",
    "msvcp140",
    "msvcp140_1",
    "msvcp140_2",
    "msvcp140_atomic_wait",
    "msvcp140_codecvt_ids",
    "vccorlib140",
    "vcomp140",
    "vcruntime140",
    "vcruntime140_1",
)

object VcRedistStep : PreInstallStep {
    override val marker: Marker = Marker.VCREDIST_INSTALLED

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean = true

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val pending = pendingEntries(container, gameDir)
        if (pending.isEmpty()) return null
        if (pending.any { isV140Installer(it.exeName) }) writeV140Overrides(container)
        return pending.joinToString(" & ") { it.commandLine }
    }

    override fun onCompleted(container: Container, gameDir: File) {
        val prefixDir = prefixDir(container) ?: return
        SteamInstallScriptRunProcess.markRun(prefixDir, pendingEntries(container, gameDir))
    }

    private fun prefixDir(container: Container): File? =
        container.rootDir?.path?.takeIf { it.isNotEmpty() }?.let { File(it, ".wine") }

    private fun pendingEntries(container: Container, gameDir: File): List<SteamInstallScriptRunProcess.Entry> {
        val prefixDir = prefixDir(container) ?: return candidates(gameDir)
        return candidates(gameDir).filter { !SteamInstallScriptRunProcess.hasRun(prefixDir, it) }
    }

    private fun candidates(gameDir: File): List<SteamInstallScriptRunProcess.Entry> {
        val scripted = SteamInstallScriptRunProcess.entries(gameDir).filter { isVcRedistExe(it.exeName) }
        if (scripted.isNotEmpty()) return scripted

        val fallback = mutableListOf<SteamInstallScriptRunProcess.Entry>()
        for ((winPath, args) in vcRedistMap) {
            if (winPath.length < 4 || winPath[1] != ':' || winPath[2] != '\\') continue
            val rest = winPath.substring(3)
            if (rest.lastIndexOf('\\') < 0) continue
            val hostFile = File(gameDir, rest.replace('\\', '/'))
            if (!hostFile.isFile) continue
            fallback += SteamInstallScriptRunProcess.Entry(winPath, args, hostFile)
        }
        val covered = vcRedistMap.keys.map { it.lowercase() }.toSet()
        File(gameDir, "_CommonRedist/vcredist").listFiles()?.sortedBy { it.name }?.forEach { yearDir ->
            if (!yearDir.isDirectory) return@forEach
            yearDir.listFiles()?.sortedBy { it.name }?.forEach { exe ->
                if (!exe.isFile || !isVcRedistExe(exe.name)) return@forEach
                val winPath = "A:\\_CommonRedist\\vcredist\\${yearDir.name}\\${exe.name}"
                if (winPath.lowercase() in covered) return@forEach
                fallback += SteamInstallScriptRunProcess.Entry(winPath, "/install /passive /norestart", exe)
            }
        }
        return fallback
    }

    private fun isVcRedistExe(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".exe") && (lower.startsWith("vc_redist") || lower.startsWith("vcredist"))
    }

    private fun isV140Installer(exeName: String): Boolean = exeName.startsWith("vc_redist", ignoreCase = true)

    private fun writeV140Overrides(container: Container) {
        val prefixDir = prefixDir(container) ?: return
        val userReg = File(prefixDir, "user.reg")
        runCatching {
            if (!userReg.isFile) {
                prefixDir.mkdirs()
                userReg.writeText("WINE REGISTRY Version 2\n\n")
            }
            WineRegistryEditor(userReg).use { editor ->
                editor.setCreateKeyIfNotExist(true)
                editor.setStringValue(DLL_OVERRIDES_KEY, "ucrtbase", "builtin")
                for (dll in v140NativeDlls) editor.setStringValue(DLL_OVERRIDES_KEY, dll, "native,builtin")
            }
        }.onFailure { Timber.w(it, "Failed to write v140 DLL overrides to ${userReg.absolutePath}") }
    }
}

