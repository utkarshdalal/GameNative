package app.gamenative.powercontrol

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import app.gamenative.PrefManager
import app.gamenative.powercontrol.PowerManager.CoreCluster
import app.gamenative.utils.ProcStatParser
import timber.log.Timber
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Places the threads that decide the frame rate, from how they behaved over the last
 * [WINDOW_MS] window, on big.LITTLE devices with power control on:
 * - the frame thread (the busiest game thread that sleeps and wakes per frame) on the
 *   highest-capacity cluster,
 * - spinners (always runnable game threads) everywhere but that cluster, so they cannot push the
 *   frame thread off it,
 * - the cross-process IPC threads (wineserver's main thread, Steam's engine IPC thread, busy
 *   wineserver and Steam threads) off the low-capacity cores.
 * Everything else keeps its affinity. Masks are applied again every window, because a winhandler
 * pin or a cpuset move on activity resume resets them.
 */
object FrameThreadPlacement {
    private const val TAG = "PowerManager"
    private const val WINDOW_MS = 2_000L
    internal const val SPINNER_MIN_CPU = 90
    internal const val SPINNER_MAX_VOLUNTARY = 50
    internal const val FRAME_MIN_CPU = 15
    internal const val FRAME_MIN_VOLUNTARY = 20
    internal const val FRAME_MAX_VOLUNTARY = 5000
    internal const val FRAME_SWITCH_RATIO = 1.2
    internal const val FRAME_SWITCH_WINDOWS = 2
    internal const val IPC_MIN_CPU = 10
    private const val MAX_RATE_THREADS = 16
    private const val MAX_REPORTED_SPINNERS = 8
    private const val ESRCH = 3

    // comm is cut at 15 characters: Steam's "IPC:CSteamEngine" reads as "IPC:CSteamEngin".
    internal const val STEAM_ENGINE_COMM = "IPC:CSteamEngin"

    private val steamProcessNames = setOf(
        "steam.exe",
        "steamservice.exe",
        "steamhost.exe",
        "steamhost32.exe",
        "steamhost64.exe",
    )

    internal enum class ProcessKind { GAME, WINESERVER, STEAM }

    internal enum class Role(val label: String) {
        FRAME("frame thread"),
        SPINNER("spinner"),
        IPC("IPC thread"),
    }

    /** One thread over the last window: [cpu] in percent of one core. */
    internal data class ThreadSample(
        val pid: Int,
        val tid: Int,
        val comm: String,
        val kind: ProcessKind,
        val cpu: Int,
        val voluntaryPerSec: Int?,
    )

    /** The frame thread and the thread that has beaten it for [challengerWindows] windows. */
    internal data class FrameChoice(
        val tid: Int,
        val comm: String,
        val challengerTid: Int? = null,
        val challengerWindows: Int = 0,
    )

    internal data class Roles(
        val frame: FrameChoice?,
        val spinners: List<ThreadSample>,
        val ipc: List<ThreadSample>,
    )

    /** Cores for each role, null when the role is left alone this session, with why in [notes]. */
    internal data class PlacementPlan(
        val frameCores: List<Int>?,
        val spinnerCores: List<Int>?,
        val ipcCores: List<Int>?,
        val allCores: List<Int>,
        val notes: List<String>,
    )

    internal data class Placement(
        val role: Role,
        val pid: Int,
        val comm: String,
        val kind: ProcessKind,
        val cores: List<Int>,
        val reason: String,
    )

    internal fun isSpinner(t: ThreadSample): Boolean =
        t.cpu >= SPINNER_MIN_CPU && t.voluntaryPerSec != null && t.voluntaryPerSec < SPINNER_MAX_VOLUNTARY

    internal fun isIpc(t: ThreadSample): Boolean = when {
        t.comm.startsWith(STEAM_ENGINE_COMM) -> true
        t.kind == ProcessKind.WINESERVER && t.tid == t.pid -> true
        t.kind != ProcessKind.GAME && t.cpu > IPC_MIN_CPU -> true
        else -> false
    }

    internal fun isFrameCandidate(t: ThreadSample): Boolean =
        t.kind == ProcessKind.GAME &&
            t.cpu >= FRAME_MIN_CPU &&
            t.voluntaryPerSec != null &&
            t.voluntaryPerSec in FRAME_MIN_VOLUNTARY..FRAME_MAX_VOLUNTARY &&
            !isSpinner(t) &&
            !isIpc(t)

    /**
     * The frame thread for this window. A held frame thread is replaced at once when it exits or
     * turns into a spinner; otherwise only by a candidate with more than [FRAME_SWITCH_RATIO] times
     * its CPU in [FRAME_SWITCH_WINDOWS] windows in a row, so a loading spike does not move it.
     */
    internal fun chooseFrame(threads: List<ThreadSample>, current: FrameChoice?): FrameChoice? {
        val best = threads.filter(::isFrameCandidate).maxByOrNull { it.cpu }
        val held = current?.let { c -> threads.firstOrNull { it.tid == c.tid } }
        if (current == null || held == null || isSpinner(held)) return best?.let { FrameChoice(it.tid, it.comm) }
        if (best == null || best.tid == held.tid || best.cpu <= held.cpu * FRAME_SWITCH_RATIO) {
            return FrameChoice(held.tid, held.comm)
        }
        val windows = if (current.challengerTid == best.tid) current.challengerWindows + 1 else 1
        return if (windows >= FRAME_SWITCH_WINDOWS) {
            FrameChoice(best.tid, best.comm)
        } else {
            FrameChoice(held.tid, held.comm, best.tid, windows)
        }
    }

    internal fun classify(threads: List<ThreadSample>, current: FrameChoice?): Roles {
        val frame = chooseFrame(threads, current)
        val ipc = threads.filter { it.tid != frame?.tid && isIpc(it) }
        val spinners = threads.filter {
            it.kind == ProcessKind.GAME && it.tid != frame?.tid && !isIpc(it) && isSpinner(it)
        }
        return Roles(frame, spinners, ipc)
    }

    /**
     * Frame thread: the highest-capacity cluster, unless every core has that capacity. Spinners:
     * every core outside it, unless those are all low-capacity cores. IPC threads: every core
     * outside the low-capacity clusters. A [gamePin] narrows each set to the cores it shares with
     * it, and a set that shares none is left alone.
     */
    internal fun placementPlan(clusters: List<CoreCluster>, gamePin: List<Int>): PlacementPlan {
        val all = clusters.flatMap { it.cores }.distinct().sorted()
        val known = clusters.filter { it.cores.isNotEmpty() && it.capacity > 0L }
        if (known.isEmpty()) {
            return PlacementPlan(null, null, null, all, listOf("cluster capacity unknown; nothing placed"))
        }
        val notes = mutableListOf<String>()
        val maxCapacity = known.maxOf { it.capacity }
        val prime = known.filter { it.capacity == maxCapacity }.flatMap { it.cores }.sorted()
        val low = PowerManager.lowCapacityCores(clusters)

        val frame = if (prime.size < all.size) prime else null
        if (frame == null) notes += "every core has the same capacity; frame thread and spinners left alone"
        val outsidePrime = all.filter { it !in prime }
        val spinner = when {
            outsidePrime.isEmpty() -> null
            outsidePrime.all { it in low } -> null.also {
                notes += "only low-capacity cores outside the prime cluster; spinners left alone"
            }
            else -> outsidePrime
        }
        val ipc = all.filter { it !in low }.takeIf { it.isNotEmpty() }

        fun withinPin(role: Role, cores: List<Int>?): List<Int>? {
            if (cores == null || gamePin.isEmpty()) return cores
            val shared = cores.filter { it in gamePin }
            if (shared.isEmpty()) {
                notes += "${role.label} cores ${PowerManager.formatRanges(cores)} outside the game pin " +
                    "${PowerManager.formatRanges(gamePin)}; ${role.label}s left alone"
                return null
            }
            return shared
        }
        return PlacementPlan(
            withinPin(Role.FRAME, frame),
            withinPin(Role.SPINNER, spinner),
            withinPin(Role.IPC, ipc),
            all,
            notes,
        )
    }

    internal fun desiredPlacements(
        threads: List<ThreadSample>,
        roles: Roles,
        plan: PlacementPlan,
    ): Map<Int, Placement> {
        val byTid = threads.associateBy { it.tid }
        val result = LinkedHashMap<Int, Placement>()
        val frame = roles.frame?.let { byTid[it.tid] }
        if (frame != null && plan.frameCores != null) {
            result[frame.tid] = Placement(
                Role.FRAME, frame.pid, frame.comm, frame.kind, plan.frameCores,
                "${frame.cpu} % cpu, ${frame.voluntaryPerSec ?: "-"} wakeups/s",
            )
        }
        if (plan.ipcCores != null) {
            roles.ipc.forEach { t ->
                val why = when {
                    t.comm.startsWith(STEAM_ENGINE_COMM) -> "Steam engine IPC"
                    t.kind == ProcessKind.WINESERVER && t.tid == t.pid -> "wineserver main thread"
                    else -> "${t.cpu} % cpu in ${t.kind.name.lowercase(Locale.US)}"
                }
                result[t.tid] = Placement(Role.IPC, t.pid, t.comm, t.kind, plan.ipcCores, why)
            }
        }
        if (plan.spinnerCores != null) {
            roles.spinners.forEach { t ->
                result[t.tid] = Placement(
                    Role.SPINNER, t.pid, t.comm, t.kind, plan.spinnerCores,
                    "${t.cpu} % cpu, ${t.voluntaryPerSec} wakeups/s",
                )
            }
        }
        return result
    }

    internal fun mask(cores: Collection<Int>): Int = cores.fold(0) { acc, core -> acc or (1 shl core) }

    // ---- session state

    private val placed = HashMap<Int, Placement>()
    private val restoreCores = HashMap<Int, List<Int>>()
    private var frame: FrameChoice? = null
    private var notesLogged = false
    private var offLogged = false

    private var reportedFrameTid: Int? = null
    private var reportedFrameComm: String? = null
    private val reportedSpinners = LinkedHashSet<Int>()
    private var placementApplied = false

    @Volatile
    private var thread: Thread? = null

    /** False once [stop] ran, also for the loop of an earlier session still waking up. */
    private fun active(): Boolean = Thread.currentThread() === thread

    /** Starts the per-window loop for a session. [envOff]: the container sets GN_FRAME_PLACEMENT=0. */
    fun start(envOff: Boolean) {
        stop("restart")
        val prefOff = runCatching { !PrefManager.frameThreadPlacement }.getOrDefault(false)
        reset()
        if (prefOff || envOff) {
            Timber.tag(TAG).i(
                "Frame thread placement off (${if (envOff) "GN_FRAME_PLACEMENT=0" else "frameThreadPlacement pref"})"
            )
            return
        }
        val loopThread = Thread(::loop, "frame-placement").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
        thread = loopThread
        loopThread.start()
    }

    /** Stops the loop and hands every placed thread its mask back. Keeps the session summary. */
    fun stop(reason: String) {
        val loopThread = thread
        thread = null
        loopThread?.interrupt()
        release(reason, TaskAffinity::set, PowerManager.threadPlacementGamePin())
    }

    @Synchronized
    internal fun release(reason: String, setAffinity: (Int, Int) -> Int, gamePin: List<Int>) {
        placed.keys.toList().forEach { tid -> releaseThread(tid, reason, setAffinity, gamePin) }
        placed.clear()
        restoreCores.clear()
        frame = null
    }

    fun properties(): Map<String, Any> = synchronized(this) {
        buildMap {
            reportedFrameTid?.let { put("frame_thread_tid", it) }
            reportedFrameComm?.let { put("frame_thread_comm", it) }
            if (reportedSpinners.isNotEmpty()) put("spinner_tids", reportedSpinners.toList())
            put("frame_placement_applied", placementApplied)
        }
    }

    @Synchronized
    internal fun reset() {
        placed.clear()
        restoreCores.clear()
        frame = null
        notesLogged = false
        offLogged = false
        reportedFrameTid = null
        reportedFrameComm = null
        reportedSpinners.clear()
        placementApplied = false
    }

    private fun loop() {
        val scanner = Scanner()
        while (active()) {
            try {
                Thread.sleep(WINDOW_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (!active()) return
            try {
                tick(scanner)
            } catch (_: InterruptedException) {
                return
            } catch (e: Throwable) {
                Timber.tag(TAG).w(e, "Frame thread placement window failed")
            }
        }
    }

    private fun tick(scanner: Scanner) {
        val gamePin = PowerManager.threadPlacementGamePin()
        val clusters = PowerManager.threadPlacementClusters()
        if (clusters == null) {
            synchronized(this) {
                if (!active()) return
                if (placed.isNotEmpty()) release("power control off", TaskAffinity::set, gamePin)
                if (!offLogged) {
                    offLogged = true
                    Timber.tag(TAG).i("Frame thread placement idle (no PServer driver or power control off)")
                }
            }
            scanner.reset()
            return
        }
        val threads = scanner.scan()
        if (threads.isEmpty()) return
        val plan = placementPlan(clusters, gamePin)
        val clamp = PowerManager.latestTunerCaps()?.primeKhz
            ?.takeIf { plan.frameCores?.size == 1 }
            ?.let { "; prime clamped to ${it / 1000} MHz by the tuner" }
            .orEmpty()
        // A stop that released everything while this window was being read wins.
        synchronized(this) {
            if (!active()) return
            applyWindow(threads, plan, gamePin, clamp, TaskAffinity::set, ::readAllowedCores)
        }
    }

    /**
     * One window: classifies [threads], applies each role's mask, and hands a thread that lost its
     * role its mask back. Logs a role change once, never the repeated apply.
     */
    @Synchronized
    internal fun applyWindow(
        threads: List<ThreadSample>,
        plan: PlacementPlan,
        gamePin: List<Int>,
        frameNote: String,
        setAffinity: (Int, Int) -> Int,
        readAllowed: (Int, Int) -> List<Int>?,
    ) {
        if (!notesLogged && plan.notes.isNotEmpty()) {
            notesLogged = true
            Timber.tag(TAG).i("Frame thread placement: ${plan.notes.joinToString("; ")}")
        }
        val roles = classify(threads, frame)
        frame = roles.frame
        roles.frame?.let {
            reportedFrameTid = it.tid
            reportedFrameComm = it.comm
        }
        roles.spinners.forEach { if (reportedSpinners.size < MAX_REPORTED_SPINNERS) reportedSpinners += it.tid }

        val desired = desiredPlacements(threads, roles, plan)
        (placed.keys - desired.keys).forEach { tid -> releaseThread(tid, "role lost", setAffinity, gamePin) }
        desired.forEach { (tid, p) ->
            val prev = placed[tid]
            if (prev == null && p.kind != ProcessKind.GAME) {
                restoreCores[tid] = readAllowed(p.pid, tid) ?: plan.allCores
            }
            val errno = setAffinity(tid, mask(p.cores))
            if (errno == 0) placementApplied = true
            if (prev == null || prev.role != p.role || prev.cores != p.cores) {
                val note = if (p.role == Role.FRAME) frameNote else ""
                val failed = if (errno != 0) "; sched_setaffinity errno $errno" else ""
                Timber.tag(TAG).i(
                    "${p.role.label} tid $tid (${p.comm}) -> cores ${PowerManager.formatRanges(p.cores)} (${p.reason}$note$failed)"
                )
            }
        }
        placed.clear()
        placed.putAll(desired)
    }

    private fun releaseThread(
        tid: Int,
        reason: String,
        setAffinity: (Int, Int) -> Int,
        gamePin: List<Int>,
    ) {
        val p = placed.remove(tid) ?: return
        val saved = restoreCores.remove(tid)
        val cores = if (p.kind == ProcessKind.GAME) {
            gamePin.ifEmpty { PowerManager.threadPlacementAllCores() }
        } else {
            saved ?: PowerManager.threadPlacementAllCores()
        }
        if (cores.isEmpty()) return
        val errno = setAffinity(tid, mask(cores))
        val outcome = when (errno) {
            0 -> ""
            ESRCH -> "; thread gone"
            else -> "; sched_setaffinity errno $errno"
        }
        Timber.tag(TAG).i(
            "${p.role.label} tid $tid (${p.comm}) released -> cores ${PowerManager.formatRanges(cores)} ($reason$outcome)"
        )
    }

    private fun readAllowedCores(pid: Int, tid: Int): List<Int>? = runCatching {
        File("/proc/$pid/task/$tid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Cpus_allowed_list:") }
                ?.substringAfter(':')
                ?.trim()
                ?.let { PowerManager.parseCpuList(it).sorted() }
                ?.takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    /** Reads this app's game, wineserver and Steam threads; a window's deltas need the window before. */
    private class Scanner {
        private val selfPid = Process.myPid()
        private val uid = Process.myUid()
        private val clkTck = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
        private var prevMs = 0L
        private var prevProcTicks = HashMap<Int, Long>()
        private var prevThreadTicks = HashMap<Int, Long>()
        private var prevVoluntary = HashMap<Int, Long>()
        private val names = HashMap<Int, Pair<String, String>>()

        fun reset() {
            prevMs = 0L
            prevProcTicks = HashMap()
            prevThreadTicks = HashMap()
            prevVoluntary = HashMap()
            names.clear()
        }

        fun scan(): List<ThreadSample> {
            val nowMs = SystemClock.elapsedRealtime()
            val dtSec = if (prevMs > 0L) (nowMs - prevMs) / 1000.0 else 0.0
            prevMs = nowMs

            val procTicks = HashMap<Int, Long>()
            val procs = ArrayList<Triple<Int, String, Int>>()
            val dirs = File("/proc").listFiles { f -> f.name.isNotEmpty() && f.name[0].isDigit() }.orEmpty()
            for (dir in dirs) {
                val pid = dir.name.toIntOrNull() ?: continue
                if (pid == selfPid) continue
                try {
                    if (Os.stat(dir.path).st_uid != uid) continue
                    val fields = ProcStatParser.parse(File(dir, "stat").readText()) ?: continue
                    val ticks = fields.utime + fields.stime
                    procTicks[pid] = ticks
                    val name = names[pid]?.takeIf { it.first == fields.comm }?.second
                        ?: ProcStatParser.processName(
                            runCatching { String(File(dir, "cmdline").readBytes()) }.getOrDefault(""),
                            fields.comm,
                        ).also { names[pid] = fields.comm to it }
                    val prev = prevProcTicks[pid] ?: continue
                    if (dtSec > 0.0) procs += Triple(pid, name.lowercase(Locale.US), ProcStatParser.cpuPercent(ticks - prev, dtSec, clkTck))
                } catch (_: Exception) {
                }
            }
            prevProcTicks = procTicks
            names.keys.retainAll(procTicks.keys)
            if (dtSec <= 0.0) {
                prevThreadTicks = HashMap()
                prevVoluntary = HashMap()
                return emptyList()
            }

            // Only Windows processes: gamescope, Xwayland or PulseAudio can outrun a light game.
            val game = procs
                .filter { it.second.endsWith(".exe") && it.second !in ProcStatParser.excludedProcessNames }
                .maxByOrNull { it.third }
            val tracked = buildList<Pair<Int, ProcessKind>> {
                game?.let { add(it.first to ProcessKind.GAME) }
                procs.filter { it.second == "wineserver" }.forEach { add(it.first to ProcessKind.WINESERVER) }
                procs.filter { it.second in steamProcessNames }.forEach { add(it.first to ProcessKind.STEAM) }
            }

            val threadTicks = HashMap<Int, Long>()
            val voluntary = HashMap<Int, Long>()
            val result = ArrayList<ThreadSample>()
            for ((pid, kind) in tracked) {
                val threads = ArrayList<Triple<Int, String, Int>>()
                for (task in File("/proc/$pid/task").listFiles().orEmpty()) {
                    val tid = task.name.toIntOrNull() ?: continue
                    val fields = try {
                        ProcStatParser.parse(File(task, "stat").readText())
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    val ticks = fields.utime + fields.stime
                    threadTicks[tid] = ticks
                    val prev = prevThreadTicks[tid] ?: continue
                    threads += Triple(tid, fields.comm, ProcStatParser.cpuPercent(ticks - prev, dtSec, clkTck))
                }
                val rated = if (kind == ProcessKind.GAME) {
                    threads.filter { it.third >= FRAME_MIN_CPU }
                        .sortedByDescending { it.third }
                        .take(MAX_RATE_THREADS)
                        .mapTo(HashSet()) { it.first }
                } else {
                    emptySet()
                }
                for ((tid, comm, cpu) in threads) {
                    var rate: Int? = null
                    if (tid in rated) {
                        val count = readVoluntary(pid, tid)
                        if (count != null) {
                            voluntary[tid] = count
                            prevVoluntary[tid]?.let { rate = ((count - it).coerceAtLeast(0L) / dtSec).roundToInt() }
                        }
                    }
                    result += ThreadSample(pid, tid, comm, kind, cpu, rate)
                }
            }
            prevThreadTicks = threadTicks
            prevVoluntary = voluntary
            return result
        }

        private fun readVoluntary(pid: Int, tid: Int): Long? = try {
            File("/proc/$pid/task/$tid/status").bufferedReader().useLines { ProcStatParser.parseCtxtSwitches(it) }?.get(0)
        } catch (_: Exception) {
            null
        }
    }
}
