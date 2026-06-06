package app.gamenative.utils.installscript

import com.winlator.xenvironment.ImageFs
import `in`.dragonbra.javasteam.types.KeyValue
import java.io.File
import timber.log.Timber

object InstallScriptParser {

    fun parse(file: File, installDir: String, language: String = "english"): InstallScript {
        return try {
            val content = file.readText()
            parseFromString(content, installDir, language, sourcePath = file.absolutePath)
        } catch (e: Exception) {
            Timber.w(e, "InstallScriptParser: failed to read file ${file.absolutePath}")
            InstallScript(sourcePath = file.absolutePath)
        }
    }

    fun parseFromString(
        content: String,
        installDir: String,
        language: String = "english",
        sourcePath: String = "",
    ): InstallScript {
        val root = try {
            KeyValue.loadFromString(content)
        } catch (e: Exception) {
            Timber.w(e, "InstallScriptParser: failed to parse VDF content")
            return InstallScript(sourcePath = sourcePath)
        }

        if (root == null) {
            Timber.w("InstallScriptParser: KeyValue.loadFromString returned null")
            return InstallScript(sourcePath = sourcePath)
        }

        val envVars = buildEnvVarMap(installDir)

        val registryActions = root.children
            .firstOrNull { it.name.equals("Registry", ignoreCase = true) }
            ?.let { parseRegistrySection(it, envVars, language) }
            ?: emptyList()

        val runProcessActions = root.children
            .firstOrNull { it.name.equals("Run Process", ignoreCase = true) }
            ?.let { parseRunProcessSection(it, envVars) }
            ?: emptyList()

        return InstallScript(
            sourcePath = sourcePath,
            registryActions = registryActions,
            runProcessActions = runProcessActions,
        )
    }

    private fun buildEnvVarMap(installDir: String): Map<String, String> {
        val user = ImageFs.USER
        return mapOf(
            "INSTALLDIR" to installDir,
            "ROOTDRIVE" to "C",
            "APPDATA" to "C:\\users\\$user\\AppData\\Roaming",
            "LOCALAPPDATA" to "C:\\users\\$user\\AppData\\Local",
            "USER_MYDOCS" to "C:\\users\\$user\\Documents",
            "COMMON_MYDOCS" to "C:\\users\\Public\\Documents",
            "WinDir" to "C:\\windows",
            "STEAMPATH" to "C:\\Program Files (x86)\\Steam",
        )
    }

    private fun expandEnvVars(value: String, envVars: Map<String, String>): String {
        var result = value
        // Build a case-insensitive lookup by lowercasing all keys
        val lowerEnvVars = envVars.entries.associate { (k, v) -> k.lowercase() to v }
        val regex = Regex("%([^%]+)%")
        result = regex.replace(result) { matchResult ->
            val varName = matchResult.groupValues[1]
            lowerEnvVars[varName.lowercase()] ?: matchResult.value
        }
        return result
    }

    private fun parseRegistrySection(
        registryNode: KeyValue,
        envVars: Map<String, String>,
        language: String,
    ): List<RegistryAction> {
        val actions = mutableListOf<RegistryAction>()
        for (keyNode in registryNode.children) {
            val keyPath = expandEnvVars(keyNode.name ?: continue, envVars)
            val baseStrings = mutableMapOf<String, String>()
            val baseDwords = mutableMapOf<String, Long>()
            val languageOverrides = mutableMapOf<String, RegistryValues>()

            for (child in keyNode.children) {
                val childName = child.name ?: continue
                when {
                    childName.equals("string", ignoreCase = true) -> {
                        for (entry in child.children) {
                            val entryName = entry.name ?: continue
                            baseStrings[entryName] = expandEnvVars(entry.value ?: "", envVars)
                        }
                    }
                    childName.equals("dword", ignoreCase = true) -> {
                        for (entry in child.children) {
                            val entryName = entry.name ?: continue
                            val raw = entry.value ?: "0"
                            baseDwords[entryName] = raw.toLongOrNull() ?: 0L
                        }
                    }
                    else -> {
                        // Language override block
                        val langStrings = mutableMapOf<String, String>()
                        val langDwords = mutableMapOf<String, Long>()
                        for (langChild in child.children) {
                            val langChildName = langChild.name ?: continue
                            when {
                                langChildName.equals("string", ignoreCase = true) -> {
                                    for (entry in langChild.children) {
                                        val entryName = entry.name ?: continue
                                        langStrings[entryName] = expandEnvVars(entry.value ?: "", envVars)
                                    }
                                }
                                langChildName.equals("dword", ignoreCase = true) -> {
                                    for (entry in langChild.children) {
                                        val entryName = entry.name ?: continue
                                        val raw = entry.value ?: "0"
                                        langDwords[entryName] = raw.toLongOrNull() ?: 0L
                                    }
                                }
                            }
                        }
                        if (langStrings.isNotEmpty() || langDwords.isNotEmpty()) {
                            languageOverrides[childName.lowercase()] = RegistryValues(
                                strings = langStrings,
                                dwords = langDwords,
                            )
                        }
                    }
                }
            }

            actions.add(
                RegistryAction(
                    keyPath = keyPath,
                    values = RegistryValues(strings = baseStrings, dwords = baseDwords),
                    languageOverrides = languageOverrides,
                ),
            )
        }
        return actions
    }

    private fun parseRunProcessSection(
        runProcessNode: KeyValue,
        envVars: Map<String, String>,
    ): List<RunProcessAction> {
        val actions = mutableListOf<RunProcessAction>()
        for (entryNode in runProcessNode.children) {
            val name = entryNode.name ?: continue
            var process = ""
            var command = ""
            var hasRunKey: String? = null
            var noCleanUp = false
            var minimumHasRunValue = 0
            var requirementOS: OSRequirement? = null
            var asCurrentUser = false

            for (child in entryNode.children) {
                val childName = child.name ?: continue
                when {
                    childName.startsWith("Process", ignoreCase = true) -> {
                        process = expandEnvVars(child.value ?: "", envVars)
                    }
                    childName.startsWith("Command", ignoreCase = true) -> {
                        command = expandEnvVars(child.value ?: "", envVars)
                    }
                    childName.equals("HasRunKey", ignoreCase = true) -> {
                        hasRunKey = expandEnvVars(child.value ?: "", envVars)
                    }
                    childName.equals("NoCleanUp", ignoreCase = true) -> {
                        noCleanUp = (child.value ?: "0") == "1"
                    }
                    childName.equals("MinimumHasRunValue", ignoreCase = true) -> {
                        minimumHasRunValue = child.value?.toIntOrNull() ?: 0
                    }
                    childName.equals("Requirement_OS", ignoreCase = true) -> {
                        val is64Bit = child.children
                            .firstOrNull { it.name?.equals("Is64BitWindows", ignoreCase = true) == true }
                            ?.value
                            ?.let { it == "1" }
                        val osType = child.children
                            .firstOrNull { it.name?.equals("OSType", ignoreCase = true) == true }
                            ?.value
                        requirementOS = OSRequirement(is64BitWindows = is64Bit, osType = osType)
                    }
                    childName.equals("AsCurrentUser", ignoreCase = true) -> {
                        asCurrentUser = (child.value ?: "0") == "1"
                    }
                }
            }

            if (process.isNotEmpty()) {
                actions.add(
                    RunProcessAction(
                        name = name,
                        process = process,
                        command = command,
                        hasRunKey = hasRunKey,
                        noCleanUp = noCleanUp,
                        minimumHasRunValue = minimumHasRunValue,
                        requirementOS = requirementOS,
                        asCurrentUser = asCurrentUser,
                    ),
                )
            }
        }
        return actions
    }
}
