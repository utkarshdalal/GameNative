package app.gamenative.utils

import app.gamenative.mods.WindowsTargetNamespace
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.util.Locale
import timber.log.Timber

object ModDllOverrides {
    private val proxies = listOf("dinput8", "winhttp", "version", "winmm", "dsound", "dwmapi", "dwrite")

    fun apply(container: Container, envVars: EnvVars) {
        try {
            val drives = Container.drivesIterator(container.drives).asSequence().associate { it[0] to File(it[1]) }
            val driveC = File(container.rootDir, ".wine/drive_c")
            val dir = resolveExecutable(container.executablePath, drives, driveC)?.parentFile ?: return
            val current = envVars.get("WINEDLLOVERRIDES")
            val added = missing(current, detect(dir))
            if (added.isEmpty()) return
            envVars.put("WINEDLLOVERRIDES", merge(current, added))
            Timber.tag("ModDllOverrides").i("Added %s for %s", added.joinToString { "$it=n,b" }, dir.path)
        } catch (e: Exception) {
            Timber.tag("ModDllOverrides").w(e, "Could not apply mod DLL overrides")
        }
    }

    fun resolveExecutable(path: String, drives: Map<String, File>, driveC: File? = null): File? {
        val normalized = path.trim().removeSurrounding("\"").replace('\\', '/')
        if (!normalized.endsWith(".exe", ignoreCase = true)) return null
        val absolute = Regex("^[a-zA-Z]:/").containsMatchIn(normalized)
        val letter = if (absolute) normalized.substring(0, 1).uppercase(Locale.ROOT) else "A"
        val relative = if (absolute) normalized.substring(3) else normalized.removePrefix("./")
        if (letter == "C" && relative.startsWith("windows/", ignoreCase = true)) return null
        val root = (if (letter == "C") driveC else null)
            ?: drives.entries.firstOrNull { it.key.equals(letter, ignoreCase = true) }?.value
            ?: return null
        return WindowsTargetNamespace(root).resolve(relative).file?.takeIf { it.isFile }
    }

    fun detect(dir: File): List<String> {
        val names = dir.list()?.map { it.lowercase(Locale.ROOT) }?.toSet() ?: return emptyList()
        return proxies.filter { "$it.dll" in names }
    }

    fun missing(value: String, detected: List<String>): List<String> {
        val mentioned = value.split(';')
            .flatMap { it.substringBefore('=').split(',', ' ', '\t') }
            .map { it.trim().replace('\\', '/').lowercase(Locale.ROOT).removeSuffix(".dll") }
            .toSet()
        return detected.distinct().filter { dll -> mentioned.none { it.substringAfterLast('/').removePrefix("*") == dll } }
    }

    fun merge(value: String, detected: List<String>): String {
        val added = missing(value, detected)
        if (added.isEmpty()) return value
        val separator = if (value.isBlank() || value.trimEnd().endsWith(';')) "" else ";"
        return value + separator + added.joinToString(";") { "$it=n,b" }
    }
}
