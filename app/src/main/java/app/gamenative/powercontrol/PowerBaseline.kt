package app.gamenative.powercontrol

import kotlinx.serialization.Serializable

/**
 * A single sysfs file and the value it held before the power session started.
 */
@Serializable
data class PowerBaselineEntry(
    val path: String,
    val value: String
)

/**
 * Snapshot of the device power state taken before any sysfs write of a session.
 * Persisted to disk so a crashed session can still be undone.
 */
@Serializable
data class PowerBaseline(
    val capturedAtMillis: Long,
    val appPid: Int,
    val entries: List<PowerBaselineEntry>,
    /**
     * Shell command that hands the fan back to the vendor controller, absent when the
     * session never took the fan over. Optional so baselines written before fan control
     * still parse.
     */
    val fanRestoreCommand: String? = null
)

/**
 * Pure helpers that build the shell payloads used to capture and restore a baseline.
 */
object PowerBaselineScripts {

    const val BASELINE_FILE_NAME = "power_baseline.json"
    const val RESTORE_SCRIPT_FILE_NAME = "power_restore.sh"
    const val DIRECTORY_NAME = "powercontrol"

    /**
     * Single command that prints "path=value" for every requested file,
     * so a whole baseline can be captured in one binder round trip.
     */
    fun buildReadCommand(paths: List<String>): String {
        return paths.joinToString("; ") { path ->
            "echo \"$path=\$(cat '$path' 2>/dev/null | head -n 1)\""
        }
    }

    fun parseReadOutput(output: String?, paths: List<String>): List<PowerBaselineEntry> {
        if (output.isNullOrBlank()) return emptyList()

        val requested = paths.toSet()
        val values = LinkedHashMap<String, String>()

        for (line in output.lines()) {
            val separator = line.indexOf('=')
            if (separator <= 0) continue
            val path = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (path in requested && value.isNotEmpty()) {
                values[path] = value
            }
        }

        return paths.mapNotNull { path -> values[path]?.let { PowerBaselineEntry(path, it) } }
    }

    fun buildRestoreScript(baseline: PowerBaseline): String = buildString {
        appendLine("#!/system/bin/sh")
        for (entry in baseline.entries) {
            appendLine("chmod 644 '${entry.path}'")
            appendLine("echo '${entry.value}' > '${entry.path}'")
        }
        baseline.fanRestoreCommand?.let { appendLine(it) }
    }

    /**
     * Must background itself and detach every fd: the caller blocks on the binder
     * transaction until the command returns.
     */
    fun buildBabysitterCommand(
        appPid: Int,
        restoreScriptPath: String,
        cleanupPaths: List<String>
    ): String {
        val removals = cleanupPaths.distinct().joinToString(" ") { "\"$it\"" }
        val cleanup = if (removals.isEmpty()) "" else "; rm -f $removals"
        return "nohup sh -c 'while kill -0 $appPid 2>/dev/null; do sleep 5; done; " +
            "sh \"$restoreScriptPath\"$cleanup' >/dev/null 2>&1 & echo \$!"
    }

    /**
     * The bracketed pattern keeps pkill from matching the shell that runs this very command.
     */
    fun buildKillBabysitterCommand(babysitterPid: Int?, restoreScriptName: String): String {
        val commands = mutableListOf<String>()
        if (babysitterPid != null && babysitterPid > 0) {
            commands.add("kill -9 $babysitterPid 2>/dev/null")
        }
        if (restoreScriptName.isNotEmpty()) {
            val pattern = "[${restoreScriptName.first()}]${restoreScriptName.drop(1)}"
            commands.add("pkill -f '$pattern' 2>/dev/null")
        }
        commands.add("true")
        return commands.joinToString("; ")
    }

    /**
     * Root daemons live in the global mount namespace, where the app's external files
     * directory is only reachable through /data/media.
     */
    fun toRootVisiblePath(path: String): String {
        val prefix = "/storage/emulated/"
        if (!path.startsWith(prefix)) return path
        val remainder = path.removePrefix(prefix)
        val user = remainder.substringBefore('/', "")
        if (user.isEmpty() || user.toIntOrNull() == null) return path
        return "/data/media/$user/${remainder.substringAfter('/', "")}"
    }

    fun describe(baseline: PowerBaseline): String {
        val entries = baseline.entries.joinToString(" ") { "${it.path}=${it.value}" }
        return baseline.fanRestoreCommand?.let { "$entries [$it]" } ?: entries
    }
}
