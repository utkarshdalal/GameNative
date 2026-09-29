package app.gamenative.utils

import app.gamenative.mods.WindowsTargetNamespace
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale

/** Recomputed for each launch. Automatic entries are never saved into the container's environment. */
object ModDllOverrides {
    private val proxies = listOf("dinput8", "winhttp", "version", "winmm", "dsound")

    // Each pair identifies the DLL itself, not another loader installed in the same directory.
    private fun wide(value: String) = value.toByteArray(Charsets.UTF_16LE).toString(Charsets.ISO_8859_1)
    private val loaderMarkers = listOf(
        listOf(wide("doorstop_config.ini"), wide("DOORSTOP_INVOKE_DLL_PATH")), // Doorstop 3/4 (BepInEx)
        listOf("IsUltimateASILoader", wide("Ultimate ASI Loader")),
        listOf("REFramework entry", "reframework_crash.dmp"),
        listOf(wide("MelonLoader.Bootstrap.dll"), wide("MelonLoader.NativeHost")), // MelonLoader 0.7
        listOf("Failed to initialize MelonLoader: ", "Failed to find MelonLoader Bootstrap"), // 0.6
        listOf(wide("MelonLoader\\Dependencies\\Bootstrap.dll"), wide("--melonloader.basedir")), // 0.5
    )
    private val markerOverlap = loaderMarkers.flatten().maxOf { it.length } - 1

    data class Result(val value: String, val added: List<String>, val preserved: List<String>)
    data class Inspection(
        val executable: File?,
        val detected: List<String>,
        val registry: RegistryOverrides,
    ) {
        fun merge(value: String): Result = ModDllOverrides.merge(value, detected, registry)
    }

    /** Shared by the editor preview and launch so both respect the same files and Wine settings. */
    fun inspect(path: String, drives: Map<String, File>, prefix: File? = null): Inspection {
        val executable = resolveExecutable(path, drives, prefix?.let { File(it, "drive_c") })
        val detected = detect(executable)
        val registry = if (prefix != null && executable != null && detected.isNotEmpty()) {
            readRegistry(File(prefix, "user.reg"), executable.name)
        } else {
            RegistryOverrides()
        }
        return Inspection(executable, detected, registry)
    }

    data class RegistryOverrides(val global: Map<String, String> = emptyMap(), val app: Map<String, String> = emptyMap()) {
        fun preserves(dll: String): Boolean {
            // An existing value may be a deliberate user choice, even if it matches an app default.
            return app.keys.any { WineDllOverrides.matches(it, dll) } ||
                global.keys.any { WineDllOverrides.matches(it, dll) }
        }
    }

    fun resolveExecutable(path: String, drives: Map<String, File>, driveC: File? = null): File? {
        val normalized = path.trim().removeSurrounding("\"").replace('\\', '/')
        if (!normalized.endsWith(".exe", ignoreCase = true)) return null
        val absolute = Regex("^[a-zA-Z]:/").containsMatchIn(normalized)
        val letter = if (absolute) normalized.substring(0, 1) else "A"
        val root = if (letter.equals("C", ignoreCase = true) && driveC != null) {
            driveC
        } else {
            drives.entries.firstOrNull { it.key.equals(letter, ignoreCase = true) }?.value
        }
        root ?: return null
        val relative = if (absolute) normalized.substring(3) else normalized.removePrefix("./")
        // Never infer mods from Wine's system directories or its built-in launcher programs.
        if (letter.equals("C", ignoreCase = true) && relative.startsWith("windows/", ignoreCase = true)) return null
        // Steam's common directory can be a symlink to a game outside drive_c. Resolve it just as Wine does.
        val executable = WindowsTargetNamespace(root).resolve(relative).takeIf { it.isValid }?.file
            ?.takeIf { it.isFile }?.canonicalFile ?: return null
        val windows = driveC?.let { File(it, "windows").canonicalFile }
        return executable.takeUnless { windows != null && it.toPath().startsWith(windows.toPath()) }
    }

    fun detect(executable: File?): List<String> {
        if (executable?.isFile != true) return emptyList()
        val files = executable.parentFile?.listFiles()?.groupBy { it.name.lowercase(Locale.ROOT) } ?: return emptyList()
        return proxies.filter { dll -> files["$dll.dll"]?.singleOrNull()?.let { it.isFile && isModLoader(it) } == true }
    }

    private fun isModLoader(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { input ->
            // Bound both I/O and memory; unknown, oversized or invalid binaries need manual overrides.
            val size = input.length()
            if (size !in 64L..32L * 1024 * 1024 || input.readUnsignedShort() != 0x4d5a) return@use false
            input.seek(0x3c)
            val peOffset = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            if (peOffset < 64 || peOffset + 24 > size) return@use false
            input.seek(peOffset)
            if (input.readInt() != 0x50450000) return@use false // PE\0\0
            input.seek(peOffset + 22)
            if (input.readUnsignedShort() and 0x0020 == 0) return@use false // IMAGE_FILE_DLL, little endian
            input.seek(0)
            val missing = loaderMarkers.map { it.toMutableSet() }
            val buffer = ByteArray(64 * 1024)
            var tail = ""
            var remaining = size
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count <= 0) return@use false
                val chunk = tail + String(buffer, 0, count, Charsets.ISO_8859_1)
                for (markers in missing) {
                    markers.removeAll { chunk.contains(it) }
                    if (markers.isEmpty()) return@use true
                }
                tail = chunk.takeLast(markerOverlap)
                remaining -= count
            }
            false
        }
    } catch (_: IOException) {
        false
    }

    fun merge(value: String, detected: List<String>, registry: RegistryOverrides = RegistryOverrides()): Result {
        val entries = WineDllOverrides.parse(value) ?: return Result(value, emptyList(), detected)
        val (preserved, added) = detected.distinct().partition { WineDllOverrides.mentions(entries, it) || registry.preserves(it) }
        if (added.isEmpty()) return Result(value, added, preserved)
        val separator = if (value.isBlank() || value.trimEnd().endsWith(';')) "" else ";"
        return Result(value + separator + added.joinToString(";") { "$it=n,b" }, added, preserved)
    }

    /** Read only the two relevant sections; do not open an editor or rewrite Wine's live registry. */
    fun readRegistry(file: File, executableName: String): RegistryOverrides {
        if (!file.exists()) return RegistryOverrides()
        val global = mutableMapOf<String, String>()
        val app = mutableMapOf<String, String>()
        val globalKey = "Software\\Wine\\DllOverrides"
        val appKey = "Software\\Wine\\AppDefaults\\$executableName\\DllOverrides"
        var target: MutableMap<String, String>? = null
        file.useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith('[')) {
                    val section = trimmed.substringAfter('[').substringBefore(']').replace("\\\\", "\\")
                    target = when {
                        section.equals(globalKey, ignoreCase = true) -> global
                        section.equals(appKey, ignoreCase = true) -> app
                        else -> null
                    }
                } else if (target != null && trimmed.startsWith('"') && trimmed.contains("\"=")) {
                    val name = trimmed.substring(1).substringBefore("\"=").replace("\\\\", "\\")
                    target?.set(name, trimmed.substringAfter("\"=").removeSurrounding("\""))
                }
            }
        }
        return RegistryOverrides(global, app)
    }
}
