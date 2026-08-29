package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import `in`.dragonbra.javasteam.types.KeyValue
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import timber.log.Timber

/**
 * Runs keyed Steam install-script processes before a direct Bionic-Steam launch.
 *
 * Bionic Steam supplies the native Steam API but intentionally does not run the
 * Windows Steam client, so Steam's normal first-run InstallScript processing is
 * otherwise skipped. Completion remains prefix-scoped by honoring each process'
 * HasRunStringKey instead of writing a marker into the shared game directory.
 */
object SteamInstallScriptStep : PreInstallStep {
    override val marker: Marker? = null

    internal data class RunProcess(
        val executable: String,
        val arguments: String,
        val hasRunStringKey: String,
        val hasRunStringValue: String,
    )

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        if (gameSource != GameSource.STEAM || !container.isLaunchBionicSteam) return false
        return parseKeyedRunProcesses(File(gameDirPath, "installScript.vdf"))
            .any { !isProcessComplete(container, it) }
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        if (gameSource != GameSource.STEAM || !container.isLaunchBionicSteam) return null
        val root = loadInstallScript(File(gameDir, "installScript.vdf")) ?: return null
        val pending = parseKeyedRunProcesses(root).filterNot { isProcessComplete(container, it) }
        if (pending.isEmpty()) return null

        applyRegistryStrings(container, gameDir, root)
        applyCopyFiles(container, gameDir, root)

        return pending.mapNotNull { process ->
            val guestExecutable = expandGuestInstallPath(process.executable) ?: return@mapNotNull null
            val hostExecutable = resolveHostInstallPath(gameDir, process.executable) ?: return@mapNotNull null
            if (!hostExecutable.isFile) {
                Timber.tag("SteamInstallScript").w("Install-script process is missing: ${hostExecutable.absolutePath}")
                return@mapNotNull null
            }
            val arguments = unattendedArguments(process)
            if (arguments.isBlank()) guestExecutable else "$guestExecutable $arguments"
        }.takeIf { it.isNotEmpty() }?.joinToString(" & ")
    }

    private fun unattendedArguments(process: RunProcess): String {
        if (!process.executable.endsWith("EAappInstaller.exe", ignoreCase = true)) {
            return process.arguments
        }

        val hasQuietFlag = Regex("(?:^|\\s)[/-](?:quiet|silent)(?:\\s|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(process.arguments)
        return buildList {
            if (!hasQuietFlag) add("/quiet /norestart")
            if (process.arguments.isNotBlank()) add(process.arguments)
        }.joinToString(" ")
    }

    internal fun parseKeyedRunProcesses(scriptFile: File): List<RunProcess> =
        loadInstallScript(scriptFile)?.let(::parseKeyedRunProcesses).orEmpty()

    private fun parseKeyedRunProcesses(root: KeyValue): List<RunProcess> {
        val runProcess = root.child("Run Process") ?: return emptyList()
        return runProcess.children.mapNotNull { entry ->
            val executable = entry.childValue("process 1")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val hasRunKey = entry.childValue("HasRunStringKey")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RunProcess(
                executable = executable,
                arguments = entry.childValue("command 1").orEmpty(),
                hasRunStringKey = hasRunKey,
                hasRunStringValue = entry.childValue("HasRunStringValue").orEmpty(),
            )
        }
    }

    private fun loadInstallScript(scriptFile: File): KeyValue? {
        if (!scriptFile.isFile) return null
        val parsed = runCatching { KeyValue.loadFromString(scriptFile.readText()) }.getOrNull() ?: return null
        return if (parsed.name.equals("InstallScript", ignoreCase = true)) parsed else parsed.child("InstallScript")
    }

    private fun isProcessComplete(container: Container, process: RunProcess): Boolean {
        val target = registryTarget(container, process.hasRunStringKey, includesValueName = true) ?: return false
        if (!target.file.isFile) return false
        return runCatching {
            WineRegistryEditor(target.file).use { editor ->
                val actual = editor.getStringValue(target.key, target.valueName, null) ?: return@use false
                process.hasRunStringValue.isBlank() || actual.equals(process.hasRunStringValue, ignoreCase = true)
            }
        }.getOrDefault(false)
    }

    private fun applyRegistryStrings(container: Container, gameDir: File, root: KeyValue) {
        val registry = root.child("Registry") ?: return
        val windowsInstallDir = "C:\\Program Files (x86)\\Steam\\steamapps\\common\\${gameDir.name}"
        for (hive in registry.children) {
            val hiveName = hive.name ?: continue
            val target = registryTarget(container, hiveName, includesValueName = false) ?: continue
            val strings = hive.child("string") ?: continue
            runCatching {
                WineRegistryEditor(target.file).use { editor ->
                    editor.setCreateKeyIfNotExist(true)
                    for (value in strings.children) {
                        val raw = value.value ?: continue
                        val valueName = value.name ?: continue
                        if (valueName.equals("(default)", ignoreCase = true)) continue
                        editor.setStringValue(
                            target.key,
                            valueName,
                            raw.replace("%INSTALLDIR%", windowsInstallDir, ignoreCase = true),
                        )
                    }
                }
            }.onFailure { Timber.tag("SteamInstallScript").w(it, "Failed to apply install-script registry strings") }
        }
    }

    private fun applyCopyFiles(container: Container, gameDir: File, root: KeyValue) {
        val copyFiles = root.child("Copy Files") ?: return
        val programData = File(container.rootDir, ".wine/drive_c/ProgramData")
        for (group in copyFiles.children) {
            val values = group.children.mapNotNull { child ->
                child.name?.let { it to child.value.orEmpty() }
            }.toMap()
            for ((name, sourceValue) in values) {
                if (!name.startsWith("SrcFile", ignoreCase = true)) continue
                val suffix = name.substring("SrcFile".length)
                val destinationValue = values.entries.firstOrNull {
                    it.key.equals("DstFile$suffix", ignoreCase = true)
                }?.value ?: continue
                val source = resolveHostInstallPath(gameDir, sourceValue) ?: continue
                val destination = resolveHostDestination(programData, destinationValue) ?: continue
                if (!source.isFile) continue
                runCatching {
                    destination.parentFile?.mkdirs()
                    Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }.onFailure { Timber.tag("SteamInstallScript").w(it, "Failed install-script copy to ${destination.absolutePath}") }
            }
        }
    }

    private data class RegistryTarget(
        val file: File,
        val key: String,
        val valueName: String = "",
    )

    private fun registryTarget(container: Container, rawPath: String, includesValueName: Boolean): RegistryTarget? {
        val normalized = rawPath.replace('/', '\\')
        val mappings = listOf(
            "HKEY_LOCAL_MACHINE_WOW64_32\\" to Pair("system.reg", "Software\\Wow6432Node\\"),
            "HKEY_LOCAL_MACHINE_WOW64_64\\" to Pair("system.reg", ""),
            "HKEY_LOCAL_MACHINE\\" to Pair("system.reg", ""),
            "HKLM\\" to Pair("system.reg", ""),
            "HKEY_CURRENT_USER\\" to Pair("user.reg", ""),
            "HKCU\\" to Pair("user.reg", ""),
        )
        val mapping = mappings.firstOrNull { normalized.startsWith(it.first, ignoreCase = true) } ?: return null
        var keyAndValue = normalized.substring(mapping.first.length)
        if (mapping.second.second.isNotEmpty() && keyAndValue.startsWith("SOFTWARE\\", ignoreCase = true)) {
            keyAndValue = mapping.second.second + keyAndValue.substring("SOFTWARE\\".length)
        }
        val valueName = if (includesValueName) keyAndValue.substringAfterLast('\\', "") else ""
        val key = if (includesValueName) keyAndValue.substringBeforeLast('\\', "") else keyAndValue
        if (key.isBlank() || (includesValueName && valueName.isBlank())) return null
        return RegistryTarget(
            file = File(container.rootDir, ".wine/${mapping.second.first}"),
            key = key,
            valueName = valueName,
        )
    }

    private fun expandGuestInstallPath(rawPath: String): String? {
        if (!rawPath.startsWith("%INSTALLDIR%", ignoreCase = true)) return null
        return "A:" + rawPath.substring("%INSTALLDIR%".length).replace('/', '\\')
    }

    private fun resolveHostInstallPath(gameDir: File, rawPath: String): File? {
        if (!rawPath.startsWith("%INSTALLDIR%", ignoreCase = true)) return null
        val relative = rawPath.substring("%INSTALLDIR%".length).trimStart('\\', '/').replace('\\', '/')
        return File(gameDir, relative)
    }

    private fun resolveHostDestination(programData: File, rawPath: String): File? {
        if (!rawPath.startsWith("%PROGRAMDATA%", ignoreCase = true)) return null
        val relative = rawPath.substring("%PROGRAMDATA%".length).trimStart('\\', '/').replace('\\', '/')
        return File(programData, relative)
    }

    private fun KeyValue.child(name: String): KeyValue? =
        children.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun KeyValue.childValue(name: String): String? = child(name)?.value
}
