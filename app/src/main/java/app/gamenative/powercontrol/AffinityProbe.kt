package app.gamenative.powercontrol

import android.os.Process
import android.system.Os
import java.io.File

/** Processes and thread affinities of this app's UID, read from /proc without root: Wine and PulseAudio run under it. */
internal object AffinityProbe {
    /** Wine's own processes, for any that doesn't run from the Windows directory. */
    private val WINE_SYSTEM_EXES = setOf(
        "services.exe", "winedevice.exe", "plugplay.exe", "explorer.exe", "rpcss.exe", "svchost.exe",
        "tabtip.exe", "winhandler.exe", "start.exe", "conhost.exe",
    )

    private val WINDOWS_DIR = Regex("""^[a-z]:\\windows\\""", RegexOption.IGNORE_CASE)
    private val EXE_END = Regex("""\.exe(?=$|[\s"])""", RegexOption.IGNORE_CASE)

    /** [exe] is the base name of what the process runs (`speed.exe`, `wineserver`), [path] its full path, [cmdline] all arguments. */
    class Proc(val pid: Int, val exe: String, val path: String, val cmdline: String)

    /** Every process of this UID except the app itself. */
    fun listOwnProcesses(): List<Proc> {
        val uid = Process.myUid()
        val self = Process.myPid()
        return File("/proc").list().orEmpty().mapNotNull { name ->
            val pid = name.toIntOrNull() ?: return@mapNotNull null
            if (pid == self || runCatching { Os.stat("/proc/$pid").st_uid }.getOrNull() != uid) return@mapNotNull null
            runCatching { File("/proc/$pid/cmdline").readBytes() }.getOrNull()?.let { parseProc(pid, it) }
        }
    }

    /**
     * [pid] from its raw /proc cmdline: a Wine process runs the first argument naming an `.exe`, which
     * may carry the rest of its command line in the same argument; anything else runs its first argument.
     */
    fun parseProc(pid: Int, cmdline: ByteArray): Proc? {
        val args = String(cmdline).split('\u0000').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        if (args.isEmpty()) return null
        val path = args.firstNotNullOfOrNull { arg ->
            EXE_END.find(arg)?.let { arg.substring(0, it.range.last + 1) }
        } ?: args.first()
        return Proc(pid, path.substringAfterLast('/').substringAfterLast('\\'), path, args.joinToString(" "))
    }

    /**
     * True for the Wine background group: wineserver, the Steam bootstrap and Wine's own processes (run from
     * the Windows directory or in [WINE_SYSTEM_EXES]). Other `.exe`s stay out, since a game started by a
     * launcher or a batch file runs under another name than [gameExe]; the game is also matched by [gamePid].
     */
    fun isBackgroundProcess(proc: Proc, gameExe: String?, gamePid: Int?): Boolean {
        if (proc.pid == gamePid || (gameExe != null && proc.exe.equals(gameExe, ignoreCase = true))) return false
        return proc.exe.startsWith("wineserver") ||
            proc.cmdline.contains("libsteambootstrap.so") ||
            proc.exe.lowercase() in WINE_SYSTEM_EXES ||
            (proc.exe.endsWith(".exe", ignoreCase = true) && WINDOWS_DIR.containsMatchIn(proc.path))
    }

    fun isAlive(pid: Int): Boolean = File("/proc/$pid").exists()

    /** A thread not allowed exactly the wanted cores, with the ones it is allowed; logs as `name[0-7]`. */
    class Stray(val tid: Int, val name: String, val allowed: Set<Int>) {
        override fun toString(): String = "$name[${PowerManager.toCpuListString(allowed)}]"
    }

    /** Threads of [pid] not allowed exactly [cores]; empty when all are, null once the process is gone. */
    fun strayThreads(pid: Int, cores: Set<Int>): List<Stray>? {
        val tasks = File("/proc/$pid/task").list() ?: return null
        return tasks.mapNotNull { task ->
            val tid = task.toIntOrNull() ?: return@mapNotNull null
            var name = task
            var allowed: Set<Int>? = null
            runCatching {
                File("/proc/$pid/task/$tid/status").forEachLine { line ->
                    when {
                        line.startsWith("Name:") -> name = line.substringAfter(':').trim()
                        line.startsWith("Cpus_allowed_list:") -> allowed = PowerManager.parseCpuList(line.substringAfter(':').trim())
                    }
                }
            }
            // A thread that exited meanwhile has no status left, which isn't a stray.
            allowed?.takeIf { it != cores }?.let { Stray(tid, name, it) }
        }
    }
}
