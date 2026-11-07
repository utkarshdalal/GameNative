package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import com.winlator.xenvironment.ImageFs
import `in`.dragonbra.javasteam.types.KeyValue
import timber.log.Timber
import java.io.File

/**
 * Applies the Registry section of a Steam game's install script (installscript.vdf) by
 * writing the values straight into the container's Wine prefix, the same way game fixes do.
 * Steam's Windows client is a 32-bit process, so HKLM\Software keys land under Wow6432Node.
 *
 * Completion is tracked by a marker in the game directory (cleared by verify) and a stamp in
 * the prefix, so the values are re-applied when either the install or the prefix is recreated.
 *
 * Every Run Process step and every common Steam redistributable is also marked as already run,
 * so Steam never launches vcredist, DXSETUP or other redistributable installers under Wine.
 */
object SteamInstallScriptRegistry {
    enum class Hive { HKLM, HKCU }
    enum class ValueType { STRING, EXPAND_STRING, DWORD }

    data class Entry(
        val hive: Hive,
        val key: String,
        val name: String?,
        val type: ValueType,
        val data: String,
    )

    data class VcRuntimeVersion(val major: Int, val minor: Int, val build: Int) : Comparable<VcRuntimeVersion> {
        override fun compareTo(other: VcRuntimeVersion): Int =
            compareValuesBy(this, other, { it.major }, { it.minor }, { it.build })

        val text: String get() = "v$major.$minor.$build.00"

        companion object {
            fun fromFileVersion(fileVersion: Long) = VcRuntimeVersion(
                (fileVersion ushr 48).toInt(),
                ((fileVersion ushr 32) and 0xFFFF).toInt(),
                ((fileVersion ushr 16) and 0xFFFF).toInt(),
            )
        }
    }

    data class RunProcessStep(
        val script: File,
        val name: String,
        val processKey: String,
        val path: String,
        val file: File?,
        val hasRunKey: String?,
        val requirementOs: Map<String, String>,
    ) {
        val requires64BitWindows: Boolean?
            get() = requirementOs.entries.firstOrNull { it.key.equals("Is64BitWindows", ignoreCase = true) }?.value?.let { it == "1" }
    }

    private const val GAME_DRIVE_ROOT = "A:\\"
    private const val DEFAULT_LANGUAGE = "english"
    private const val USER_PROFILE = "C:\\users\\${ImageFs.USER}"
    private const val RUN_PROCESS_STAMP = ".steam_install_script_run_process"
    private const val STEAM_APPS_KEY = "Software\\Valve\\Steam\\Apps"
    private const val COMMON_REDIST_DIR = "_CommonRedist"
    private const val INSTALL_SCRIPT_NAME = "installscript.vdf"
    private const val COMMON_REDIST_SCAN_DEPTH = 3
    private const val REDIST_MARKS_VERSION = 2
    private const val REDIST_MARKS_STAMP = ".steam_redist_marks_v$REDIST_MARKS_VERSION"
    private const val COMMON_REDIST_KEY = "Software\\Valve\\Steam\\Apps\\CommonRedist"
    private const val VC_RUNTIMES_KEY = "Software\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes"
    internal val DEFAULT_VC_RUNTIME = VcRuntimeVersion(14, 44, 35211)
    private val VC_RUNTIME_ARCHES = listOf("x86", "x64", "arm64")

    internal val COMMON_REDIST_MARKS: List<Pair<String, String>> = listOf(
        "DirectX\\Jun2010" to "dxsetup",
        ".NET\\4.8" to "4.8",
        "vcredist\\2008" to "x86 SP1",
        "vcredist\\2008" to "x64 SP1",
        "vcredist\\2010" to "x86",
        "vcredist\\2010" to "x64",
        "vcredist\\2013" to "x86 12.0.30501",
        "vcredist\\2013" to "x64 12.0.30501",
        "vcredist\\2015" to "x86 Update 3 14.0.24215.0",
        "vcredist\\2015" to "x64 Update 3 14.0.24215.0",
        "vcredist\\2017" to "x86 14.10.25008.0",
        "vcredist\\2017" to "x64 14.10.25008.0",
        "vcredist\\2019" to "x86 14.28.29334.0",
        "vcredist\\2019" to "x64 14.28.29334.0",
        "vcredist\\2022" to "x86 14.51.36247.0",
        "vcredist\\2022" to "x64 14.51.36247.0",
    )

    private val PROCESS_KEY_PATTERN = Regex("(?i)^process\\s*(\\d+)$")
    private val COMMAND_KEY_PATTERN = Regex("(?i)^command\\s*(\\d+)$")
    private val REDIST_PROCESS = Regex(
        "(?i)vc_redist|vcredist|msvc|ue4?prereqsetup|dxsetup|dxwebsetup|directx|dotnet|netfx|ndp4|\\.net|xna|physx|oalinst|openal|" +
            "eaappinstaller|eadesktop|originsetup|originthinsetup|\\\\__installer\\\\origin\\\\|uplay|ubisoftconnect|" +
            "ubisoft game launcher|rockstar-games-launcher",
    )
    private const val MAX_SCRIPT_BYTES = 64 * 1024
    private val TOKEN_PATTERN = Regex("(?i)%([A-Z_]+)%([\\\\/]?)")

    private fun tokens(installDir: String): Map<String, String> = mapOf(
        "INSTALLDIR" to installDir,
        "ROOTDRIVE" to installDir.substringBefore(':'),
        "WINDIR" to "C:\\windows",
        "APPDATA" to "$USER_PROFILE\\AppData\\Roaming",
        "LOCALAPPDATA" to "$USER_PROFILE\\AppData\\Local",
        "USER_MYDOCS" to "$USER_PROFILE\\Documents",
        "COMMON_MYDOCS" to "C:\\users\\Public\\Documents",
        "STEAMPATH" to "C:\\Program Files (x86)\\Steam",
    )

    fun applyForLaunch(container: Container, appId: String) {
        val prefixDir = File(container.rootDir, ".wine")
        try {
            writeRedistMarks(prefixDir)
        } catch (e: Exception) {
            Timber.w(e, "Failed to write Steam redistributable marks")
        }
        if (ContainerUtils.extractGameSourceFromContainerId(appId) != GameSource.STEAM) return
        try {
            applyInstallScript(container, appId, prefixDir)
        } catch (e: Exception) {
            Timber.w(e, "Failed to apply Steam install script for $appId")
        }
    }

    private fun applyInstallScript(container: Container, appId: String, prefixDir: File) {
        val numericAppId = ContainerUtils.extractGameIdFromContainerId(appId)
        val gameDir = PreInstallSteps.getGameDir(container) ?: return
        val scriptName = SteamService.getAppInfoOf(numericAppId)?.installScript
        val scriptFile = gameScriptFile(gameDir, scriptName)
        try {
            if (scriptFile != null) applyRegistrySection(container, numericAppId, gameDir, prefixDir, scriptFile)
        } catch (e: Exception) {
            Timber.w(e, "Failed to apply install-script registry for app $numericAppId")
        }
        try {
            applyRunProcessMarks(prefixDir, gameDir, numericAppId, scriptFile)
        } catch (e: Exception) {
            Timber.w(e, "Failed to apply run-process marks for app $numericAppId")
        }
    }

    private fun applyRegistrySection(container: Container, appId: Int, gameDir: File, prefixDir: File, scriptFile: File) {
        val prefixStamp = File(prefixDir, "${Marker.STEAM_INSTALL_SCRIPT_INSTALLED.fileName}_$appId")
        val entries = parse(scriptFile.readText(), GAME_DRIVE_ROOT, container.language)
        if (
            MarkerUtils.hasMarker(gameDir.absolutePath, Marker.STEAM_INSTALL_SCRIPT_INSTALLED) &&
            prefixStamp.isFile &&
            allPresent(prefixDir, entries)
        ) return

        write(prefixDir, entries)
        Timber.i("Applied ${entries.size} install-script registry values for app $appId")

        MarkerUtils.addMarker(gameDir.absolutePath, Marker.STEAM_INSTALL_SCRIPT_INSTALLED)
        prefixDir.mkdirs()
        runCatching { prefixStamp.createNewFile() }
    }

    internal fun applyRunProcessMarks(prefixDir: File, gameDir: File, appId: Int, scriptFile: File?): Boolean {
        val scripts = (listOfNotNull(scriptFile) + findCommonRedistScripts(gameDir)).distinct()
        if (scripts.isEmpty()) return false
        if (!File(prefixDir, "system.reg").isFile) {
            Timber.w("Skipping run-process marks, no system.reg in ${prefixDir.absolutePath}")
            return false
        }
        val stamp = File(prefixDir, "${RUN_PROCESS_STAMP}_$appId")
        val signature = scripts.joinToString("\n") { "${it.relativeTo(gameDir).path}:${it.length()}:${it.lastModified()}" }
        var unreadable = false
        val entries = scripts.flatMap { script ->
            runCatching { parseRunProcessMarks(script.readText(), appId, gameDir) }
                .onFailure {
                    unreadable = true
                    Timber.w(it, "Failed to read ${script.absolutePath}")
                }
                .getOrDefault(emptyList())
        }.distinct()
        // the stamp alone is not proof: a wineserver flush can drop marks that were edited into
        // system.reg, so a stamped prefix is only skipped while every mark is still present
        if (stamp.isFile && runCatching { stamp.readText() }.getOrNull() == signature && allPresent(prefixDir, entries)) return false

        write(prefixDir, entries, keepHigherDwords = true)
        if (!unreadable) stamp.writeText(signature)
        Timber.i("Applied ${entries.size} run-process marks from ${scripts.size} install scripts for app $appId")
        return true
    }

    internal fun allPresent(prefixDir: File, entries: List<Entry>): Boolean =
        Hive.entries.all { hive -> allPresentIn(prefixDir, hive, entries.filter { it.hive == hive }) }

    private fun allPresentIn(prefixDir: File, hive: Hive, entries: List<Entry>): Boolean {
        if (entries.isEmpty()) return true
        val regFile = File(prefixDir, if (hive == Hive.HKLM) "system.reg" else "user.reg")
        if (!regFile.isFile) return false
        return WineRegistryEditor(regFile).use { editor -> entries.all { isPresent(editor, it) } }
    }

    private fun isPresent(editor: WineRegistryEditor, entry: Entry): Boolean = when (entry.type) {
        ValueType.DWORD -> {
            val required = unsignedDword(entry.data)
            val stored = storedDword(editor, entry.key, entry.name)
            required == null || (stored != null && stored >= required)
        }
        ValueType.STRING -> editor.getStringValue(entry.key, entry.name) == entry.data
        ValueType.EXPAND_STRING -> editor.getStringValue(entry.key, entry.name) != null
    }

    private fun dwordOf(data: String): Int? = runCatching { java.lang.Long.decode(data.trim()).toInt() }.getOrNull()

    private fun unsignedDword(data: String): Long? = dwordOf(data)?.let { it.toLong() and 0xFFFFFFFFL }

    internal fun storedDword(editor: WineRegistryEditor, key: String, name: String?): Long? =
        runCatching { editor.getDwordValue(key, name) }.getOrNull()?.let { it.toLong() and 0xFFFFFFFFL }

    internal fun gameScriptFile(gameDir: File, scriptName: String?): File? {
        val name = scriptName?.trim().orEmpty().ifEmpty { INSTALL_SCRIPT_NAME }
        return resolveChildCaseInsensitive(gameDir, name).also {
            if (it == null && name != INSTALL_SCRIPT_NAME) Timber.w("Install script $name not found in ${gameDir.absolutePath}")
        }
    }

    fun runProcessSteps(gameDir: File, scriptName: String?): List<RunProcessStep> {
        val gameScript = gameScriptFile(gameDir, scriptName)
        val scripts = (listOfNotNull(gameScript) + findCommonRedistScripts(gameDir)).distinct()
        return scripts.flatMap { script ->
            runCatching { parseRunProcessSteps(script.readText(), script, gameDir) }
                .onFailure { Timber.w(it, "Failed to read ${script.absolutePath}") }
                .getOrDefault(emptyList())
        }
    }

    internal fun parseRunProcessSteps(vdf: String, script: File, gameDir: File): List<RunProcessStep> {
        val root = runCatching { KeyValue.loadFromString(vdf) }.getOrNull() ?: return emptyList()
        val runProcess = root["InstallScript"]["Run Process"].takeUnless { it === KeyValue.INVALID }
            ?: root["Run Process"].takeUnless { it === KeyValue.INVALID }
            ?: return emptyList()
        val tokens = tokens(GAME_DRIVE_ROOT)
        val steps = mutableListOf<RunProcessStep>()
        for (step in runProcess.children) {
            val stepName = step.name?.trim().orEmpty()
            if (stepName.isEmpty() || step.children.isEmpty()) continue
            val requirementOs = step["Requirement_OS"].children
                .filter { it.name != null && it.value != null }
                .associate { it.name!! to it.value!!.trim() }
            val processes = step.children
                .mapNotNull { child ->
                    val index = PROCESS_KEY_PATTERN.find(child.name?.trim().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    val value = child.value?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
                    if (index == null || value == null) null else Triple(index, child.name!!.trim(), value)
                }
                .sortedBy { it.first }
            for ((_, key, value) in processes) {
                val path = expandTokens(value, tokens)
                steps += RunProcessStep(
                    script = script,
                    name = stepName,
                    processKey = key,
                    path = path,
                    file = hostFileFor(gameDir, path),
                    hasRunKey = step.stringValue("HasRunKey"),
                    requirementOs = requirementOs,
                )
            }
        }
        return steps
    }

    private fun hostFileFor(gameDir: File, windowsPath: String): File? {
        val relative = when {
            windowsPath.startsWith(GAME_DRIVE_ROOT, ignoreCase = true) -> windowsPath.substring(GAME_DRIVE_ROOT.length)
            windowsPath.length >= 2 && windowsPath[1] == ':' -> return null
            windowsPath.startsWith("\\") || windowsPath.startsWith("/") -> return null
            else -> windowsPath
        }
        return resolveChildCaseInsensitive(gameDir, relative)
    }

    internal fun findCommonRedistScripts(gameDir: File): List<File> {
        val redistDir = gameDir.listFiles()
            ?.firstOrNull { it.isDirectory && it.name.equals(COMMON_REDIST_DIR, ignoreCase = true) }
            ?: return emptyList()
        return redistDir.walkTopDown()
            .maxDepth(COMMON_REDIST_SCAN_DEPTH + 1)
            .filter { it.isFile && it.name.equals(INSTALL_SCRIPT_NAME, ignoreCase = true) }
            .sortedBy { it.path }
            .toList()
    }

    /** A re-extracted prefix template brings fresh registry files; every mark stamp must go with them. */
    fun clearPrefixStamps(prefixDir: File) {
        prefixDir.listFiles().orEmpty()
            .filter { it.name.startsWith(".steam_redist_marks_v") || it.name.startsWith(".steam_install_script_") }
            .forEach { it.delete() }
    }

    internal fun writeRedistMarks(prefixDir: File): Boolean {
        val stamp = File(prefixDir, REDIST_MARKS_STAMP)
        if (stamp.isFile) return false
        if (!File(prefixDir, "system.reg").isFile) {
            Timber.w("Skipping Steam redistributable marks, no system.reg in ${prefixDir.absolutePath}")
            return false
        }
        val entries = commonRedistMarkEntries()
        write(prefixDir, entries)
        raiseVcRuntimeVersion(prefixDir, DEFAULT_VC_RUNTIME)
        stamp.createNewFile()
        Timber.i("Wrote ${entries.size} Steam redistributable marks")
        return true
    }

    internal fun redistMarkEntries(): List<Entry> =
        commonRedistMarkEntries() + vcRuntimeKeys().flatMap { vcRuntimeEntries(it, DEFAULT_VC_RUNTIME) }

    private fun commonRedistMarkEntries(): List<Entry> =
        COMMON_REDIST_MARKS.flatMap { (subKey, valueName) ->
            bothViews("$COMMON_REDIST_KEY\\$subKey").map { view -> Entry(Hive.HKLM, view, valueName, ValueType.DWORD, "1") }
        }

    internal fun vcRuntimeKeys(): List<String> = VC_RUNTIME_ARCHES.flatMap { bothViews("$VC_RUNTIMES_KEY\\$it") }

    internal fun vcRuntimeEntries(key: String, version: VcRuntimeVersion): List<Entry> = listOf(
        Entry(Hive.HKLM, key, "Installed", ValueType.DWORD, "1"),
        Entry(Hive.HKLM, key, "Major", ValueType.DWORD, version.major.toString()),
        Entry(Hive.HKLM, key, "Minor", ValueType.DWORD, version.minor.toString()),
        Entry(Hive.HKLM, key, "Bld", ValueType.DWORD, version.build.toString()),
        Entry(Hive.HKLM, key, "Rbld", ValueType.DWORD, "0"),
        Entry(Hive.HKLM, key, "Version", ValueType.STRING, version.text),
    )

    /**
     * Raises the VC++ 2015-2022 runtime keys of every architecture, in both registry views, to
     * [version]. A key that already records the same or a higher version is left alone.
     */
    fun raiseVcRuntimeVersion(prefixDir: File, version: VcRuntimeVersion): Boolean {
        val systemReg = File(prefixDir, "system.reg")
        if (!systemReg.isFile) return false
        val stale = WineRegistryEditor(systemReg).use { editor ->
            vcRuntimeKeys().filter { key -> installedVcRuntime(editor, key)?.let { it < version } ?: true }
        }
        if (stale.isEmpty()) return false
        write(prefixDir, stale.flatMap { vcRuntimeEntries(it, version) })
        Timber.i("Raised ${stale.size} VC runtime keys to ${version.text}")
        return true
    }

    private fun installedVcRuntime(editor: WineRegistryEditor, key: String): VcRuntimeVersion? {
        val major = editor.getDwordValue(key, "Major") ?: return null
        return VcRuntimeVersion(major, editor.getDwordValue(key, "Minor") ?: 0, editor.getDwordValue(key, "Bld") ?: 0)
    }

    /**
     * True for steps Steam would satisfy with a redistributable or launcher install: a `process N`
     * or `command N` naming one, a .cmd whose body names one, or a step that runs no process.
     */
    internal fun isRedistStep(step: KeyValue, gameDir: File?): Boolean {
        val processes = indexedValues(step, PROCESS_KEY_PATTERN).filterValues { it.isNotEmpty() }
        if (processes.isEmpty()) return true
        val commands = indexedValues(step, COMMAND_KEY_PATTERN)
        return processes.all { (index, process) ->
            namesRedist(process) || commands[index]?.let(::namesRedist) == true || batchStartsRedist(process, gameDir)
        }
    }

    private fun namesRedist(text: String): Boolean = REDIST_PROCESS.containsMatchIn(text.replace('/', '\\'))

    private fun indexedValues(step: KeyValue, pattern: Regex): Map<Int, String> =
        step.children.mapNotNull { child ->
            val index = pattern.find(child.name?.trim().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            val value = child.value?.trim()?.trim('"')
            if (index == null || value == null) null else index to value
        }.toMap()

    private fun batchStartsRedist(process: String, gameDir: File?): Boolean {
        if (!process.endsWith(".cmd", ignoreCase = true) && !process.endsWith(".bat", ignoreCase = true)) return false
        val script = gameDir?.let { hostFileFor(it, expandTokens(process, tokens(GAME_DRIVE_ROOT))) } ?: return false
        val body = runCatching {
            script.inputStream().use { input ->
                val buffer = ByteArray(MAX_SCRIPT_BYTES)
                var total = 0
                while (total < buffer.size) {
                    val read = input.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                String(buffer, 0, total, Charsets.ISO_8859_1)
            }
        }.getOrNull()
        return body != null && namesRedist(body)
    }

    internal fun parseRunProcessMarks(vdf: String, appId: Int, gameDir: File? = null): List<Entry> {
        val root = runCatching { KeyValue.loadFromString(vdf) }.getOrNull() ?: return emptyList()
        val runProcess = root["InstallScript"]["Run Process"].takeUnless { it === KeyValue.INVALID }
            ?: root["Run Process"].takeUnless { it === KeyValue.INVALID }
            ?: return emptyList()

        val entries = mutableListOf<Entry>()
        for (step in runProcess.children) {
            val stepName = step.name?.trim().orEmpty()
            if (stepName.isEmpty() || step.children.isEmpty()) continue
            if (!isRedistStep(step, gameDir)) {
                Timber.d("Leaving install-script step $stepName unmarked, it does not install a redistributable")
                continue
            }
            val valueName = step.stringValue("RunKeyName") ?: stepName
            val minimum = step.stringValue("MinimumHasRunValue")
                ?.let { runCatching { java.lang.Long.decode(it).toInt() }.getOrNull() }
                ?: 1
            val value = maxOf(1, minimum).toString()

            for (key in bothViews("$STEAM_APPS_KEY\\$appId")) {
                entries += Entry(Hive.HKLM, key, valueName, ValueType.DWORD, value)
            }

            step.stringValue("HasRunKey")?.let { hasRunKey ->
                val split = splitHive(hasRunKey)
                if (split == null) {
                    Timber.d("Skipping unsupported HasRunKey $hasRunKey for step $stepName")
                } else {
                    for (key in viewsFor(split.first, split.second)) {
                        entries += Entry(split.first, key, valueName, ValueType.DWORD, value)
                    }
                }
            }

            step.stringValue("HasRunStringKey")?.let { stringKey ->
                val split = splitHive(stringKey)
                if (split == null) {
                    Timber.d("Skipping unsupported HasRunStringKey $stringKey for step $stepName")
                    return@let
                }
                val (hive, path) = split
                val data = step["HasRunStringValue"].value ?: "true"
                val separator = path.lastIndexOf('\\')
                if (separator > 0) {
                    val parent = path.substring(0, separator)
                    val leaf = path.substring(separator + 1)
                    for (key in viewsFor(hive, parent)) {
                        entries += Entry(hive, key, leaf, ValueType.STRING, data)
                    }
                }
            }
        }
        return entries.distinct()
    }

    private fun KeyValue.stringValue(name: String): String? =
        this[name].value?.trim()?.takeIf { it.isNotEmpty() }

    private fun viewsFor(hive: Hive, path: String): List<String> =
        if (hive == Hive.HKLM) bothViews(path) else listOf(path)

    internal fun parse(vdf: String, installDir: String, language: String = DEFAULT_LANGUAGE): List<Entry> {
        val root = runCatching { KeyValue.loadFromString(vdf) }.getOrNull() ?: return emptyList()
        val registry = root["InstallScript"]["Registry"].takeUnless { it === KeyValue.INVALID }
            ?: root["Registry"].takeUnless { it === KeyValue.INVALID }
            ?: return emptyList()

        val tokens = tokens(installDir)
        val entries = mutableListOf<Entry>()
        for (key in registry.children) {
            val split = splitHive(expandTokens(key.name.orEmpty(), tokens))
            if (split == null) {
                Timber.d("Skipping unsupported registry key ${key.name}")
                continue
            }
            val (hive, path) = split
            val redirected = if (hive == Hive.HKLM) redirectTo32BitView(path) else path
            addValues(entries, hive, redirected, key["string"], ValueType.STRING, tokens, language)
            addValues(entries, hive, redirected, key["expandstring"], ValueType.EXPAND_STRING, tokens, language)
            addValues(entries, hive, redirected, key["dword"], ValueType.DWORD, tokens, language)
        }
        return entries
    }

    /**
     * [keepHigherDwords] leaves a DWORD alone when the registry already holds a larger value, so a
     * run-process mark written from a script's MinimumHasRunValue never lowers a version another
     * component (PhysX, VC runtimes) has put there.
     */
    internal fun write(prefixDir: File, entries: List<Entry>, keepHigherDwords: Boolean = false) {
        for (hive in Hive.entries) {
            val hiveEntries = entries.filter { it.hive == hive }
            if (hiveEntries.isEmpty()) continue
            val regFile = File(prefixDir, if (hive == Hive.HKLM) "system.reg" else "user.reg")
            if (!regFile.isFile) {
                regFile.parentFile?.mkdirs()
                regFile.writeText("WINE REGISTRY Version 2\n\n")
            }
            WineRegistryEditor(regFile).use { editor ->
                editor.setCreateKeyIfNotExist(true)
                for (entry in hiveEntries) {
                    when (entry.type) {
                        ValueType.STRING -> editor.setStringValue(entry.key, entry.name, entry.data)
                        ValueType.EXPAND_STRING -> editor.setExpandStringValue(entry.key, entry.name, entry.data)
                        ValueType.DWORD -> {
                            val value = dwordOf(entry.data)
                            if (value == null) {
                                Timber.w("Skipping non-numeric dword ${entry.key}\\${entry.name}=${entry.data}")
                            } else if (keepHigherDwords && (storedDword(editor, entry.key, entry.name) ?: -1L) > (value.toLong() and 0xFFFFFFFFL)) {
                                Timber.d("Keeping ${entry.key}\\${entry.name}, already above $value")
                            } else {
                                editor.setDwordValue(entry.key, entry.name, value)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun addValues(
        entries: MutableList<Entry>,
        hive: Hive,
        key: String,
        values: KeyValue,
        type: ValueType,
        tokens: Map<String, String>,
        language: String,
    ) {
        if (values === KeyValue.INVALID) return
        val (languageBlocks, plainValues) = values.children.partition { it.value == null && it.children.isNotEmpty() }
        val selectedBlock = languageBlocks.firstOrNull { it.name.equals(language, ignoreCase = true) }
            ?: languageBlocks.firstOrNull { it.name.equals(DEFAULT_LANGUAGE, ignoreCase = true) }
        for (value in plainValues + selectedBlock?.children.orEmpty()) {
            entries += Entry(
                hive = hive,
                key = key,
                name = value.name.orEmpty().takeUnless { it.isEmpty() || it.equals("(Default)", ignoreCase = true) },
                type = type,
                data = expandTokens(value.value.orEmpty(), tokens),
            )
        }
    }

    private fun splitHive(path: String): Pair<Hive, String>? {
        val separator = path.indexOf('\\')
        val hiveName = if (separator < 0) path else path.substring(0, separator)
        val rest = if (separator < 0) "" else path.substring(separator + 1).trim('\\')
        if (rest.isEmpty()) return null
        val hive = when (hiveName.uppercase()) {
            "HKEY_LOCAL_MACHINE", "HKLM" -> Hive.HKLM
            "HKEY_CURRENT_USER", "HKCU" -> Hive.HKCU
            else -> return null
        }
        return hive to rest
    }

    private fun redirectTo32BitView(path: String): String {
        val segments = path.split('\\')
        if (segments.size < 2 || !segments[0].equals("Software", ignoreCase = true)) return path
        if (segments[1].equals("Wow6432Node", ignoreCase = true)) return path
        return (listOf(segments[0], "Wow6432Node") + segments.drop(1)).joinToString("\\")
    }

    private fun bothViews(path: String): List<String> {
        val segments = path.split('\\').filter { it.isNotEmpty() }
        if (segments.isEmpty() || !segments[0].equals("Software", ignoreCase = true)) return listOf(path)
        val rest = segments.drop(1).let { if (it.firstOrNull().equals("Wow6432Node", ignoreCase = true)) it.drop(1) else it }
        if (rest.isEmpty()) return listOf(path)
        return listOf(
            (listOf("Software", "Wow6432Node") + rest).joinToString("\\"),
            (listOf("Software") + rest).joinToString("\\"),
        )
    }

    private fun expandTokens(value: String, tokens: Map<String, String>): String =
        TOKEN_PATTERN.replace(value) { match ->
            val replacement = tokens[match.groupValues[1].uppercase()] ?: return@replace match.value
            val separator = match.groupValues[2]
            if (separator.isEmpty()) replacement else replacement.trimEnd('\\', '/') + "\\"
        }

    private fun resolveChildCaseInsensitive(root: File, relativePath: String): File? {
        var current = root
        for (segment in relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }) {
            if (segment == "." || segment == "..") return null
            current = current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) }
                ?: return null
        }
        return current.takeIf { it.isFile }
    }
}
