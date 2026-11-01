package app.gamenative.powercontrol.metrics

import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * GPU load measured by the gamescope ICD shim (gn-vkshim) from per-submit timestamps.
 *
 * Under gamescope every Vulkan process of the session (the guest, gamescope, its Xwayland)
 * goes through the shim. With `GN_GPUTIME_DIR` set, each one keeps a file named after its
 * pid in that directory and replaces it about once a second with one line of cumulative
 * counters:
 *
 * `v1 <role> <busy_ns> <wall_ns> <presents> <untimed> <updated_mono_ns> <devices> [<timed>]`
 *
 * Busy time is the union of the GPU intervals of the process's submits; `updated_mono_ns`
 * is CLOCK_MONOTONIC, the clock behind [System.nanoTime]. This reads the files, turns them
 * into per-pid deltas and offers the result as the last-resort GPU usage source
 * ([SOURCE]), as the guest's GPU time per frame ([GpuFrameTime]), and as session
 * telemetry. It works on any GPU vendor, but only where the shim runs and only with a
 * payload that has the GPU-time export.
 */
object ShimGpuTime {
    const val SOURCE = "shim/timestamps"
    private const val STALE_NS = 2_000_000_000L

    data class Record(
        val role: String,
        val busyNs: Long,
        val wallNs: Long,
        val presents: Long,
        val untimed: Long,
        val updatedNs: Long,
        val devices: Int,
        val timed: Long?,
    )

    /** Last record of a pid and the window it closed. */
    data class PidState(
        val record: Record,
        val util: Double? = null,
        val busyDeltaNs: Long = 0L,
        val presentsDelta: Long = 0L,
    )

    data class Step(
        val states: Map<String, PidState>,
        val percent: Int?,
        val frameMs: Float?,
        val guestUpdated: Boolean,
        val devices: Int,
    )

    /** The directory the launcher points the shim at, under the image's root. */
    @JvmStatic
    fun directory(rootDir: File): File = File(rootDir, "tmp/gputime")

    /** Parses one export line; null for anything that is not a v1 record. */
    fun parse(line: String): Record? {
        val f = line.trim().split(Regex("\\s+"))
        if (f.size < 8 || f[0] != "v1") return null
        val n = f.subList(2, 8).map { it.toLongOrNull() ?: return null }
        if (n.any { it < 0L }) return null
        return Record(
            role = f[1],
            busyNs = n[0],
            wallNs = n[1],
            presents = n[2],
            untimed = n[3],
            updatedNs = n[4],
            devices = n[5].toInt(),
            timed = f.getOrNull(8)?.toLongOrNull(),
        )
    }

    /**
     * Advances the per-pid states with the files read now. A pid's window closes when its
     * file has changed; usage is the sum over fresh pids of their last window's busy/wall
     * (capped at 100), frame time the guest pids' busy over presents.
     */
    fun step(prev: Map<String, PidState>, files: Map<String, Record>, nowNs: Long): Step {
        val states = HashMap<String, PidState>()
        var guestUpdated = false
        for ((pid, rec) in files) {
            val old = prev[pid]
            states[pid] = when {
                old == null || old.record.role != rec.role -> PidState(rec)
                rec.updatedNs == old.record.updatedNs -> old
                else -> {
                    val dBusy = rec.busyNs - old.record.busyNs
                    val dWall = rec.wallNs - old.record.wallNs
                    val dPresents = rec.presents - old.record.presents
                    if (dBusy < 0L || dWall <= 0L || dPresents < 0L) {
                        // Counters went backwards: a reused pid; start over.
                        PidState(rec)
                    } else {
                        if (rec.role == "guest") guestUpdated = true
                        PidState(rec, dBusy.toDouble() / dWall.toDouble(), dBusy, dPresents)
                    }
                }
            }
        }
        val fresh = states.values.filter { nowNs - it.record.updatedNs <= STALE_NS }
        val utils = fresh.mapNotNull { it.util }
        val percent = if (utils.isEmpty()) null else (utils.sum() * 100.0).roundToInt().coerceIn(0, 100)
        val guests = fresh.filter { it.record.role == "guest" && it.util != null }
        val presents = guests.sumOf { it.presentsDelta }
        val frameMs = if (presents > 0L) (guests.sumOf { it.busyDeltaNs }.toDouble() / presents / 1e6).toFloat() else null
        return Step(states, percent, frameMs, guestUpdated, fresh.sumOf { it.record.devices })
    }

    /** Share of submits that carried timestamps; null before any submit was seen. */
    fun coverage(records: Collection<Record>): Double? {
        val timed = records.sumOf { it.timed ?: 0L }
        val total = timed + records.sumOf { it.untimed }
        return if (total > 0L) timed.toDouble() / total else null
    }

    private val lock = Any()
    private var dir: File? = null
    private var states: Map<String, PidState> = emptyMap()
    private val seen = HashMap<String, Record>()
    private var devicesSeen = 0

    @Volatile
    private var latest: GpuUsageReading? = null

    /** Starts a session: empties the directory and forgets the previous session. */
    fun start(directory: File) {
        synchronized(lock) {
            dir = directory
            states = emptyMap()
            seen.clear()
            devicesSeen = 0
            latest = null
        }
        GpuFrameTime.reset()
        runCatching {
            directory.mkdirs()
            directory.listFiles()?.forEach { it.delete() }
        }
    }

    /** Reads the directory once; call from the metrics collector's cycle. */
    fun update(nowNs: Long = System.nanoTime()) {
        val d = synchronized(lock) { dir } ?: return
        val files = d.listFiles { f -> !f.name.startsWith(".") }
            ?.mapNotNull { f -> runCatching { f.readText() }.getOrNull()?.let(::parse)?.let { f.name to it } }
            ?.toMap()
            ?: return
        val result = synchronized(lock) {
            val s = step(states, files, nowNs)
            states = s.states
            seen.putAll(files)
            if (s.devices > devicesSeen) devicesSeen = s.devices
            latest = s.percent?.let { GpuUsageReading(it, SOURCE) }
            s
        }
        GpuFrameTime.lastFrameMs = result.frameMs
        if (result.guestUpdated) result.frameMs?.let { PowerTelemetry.recordGpuFrameMs(it) }
    }

    /** Latest usage from the shim, or null when no session process has a fresh file. */
    fun sample(): GpuUsageReading? = latest

    /** Session keys; empty when the shim never wrote a file this session. */
    fun sessionProperties(): Map<String, Any> = synchronized(lock) {
        if (seen.isEmpty()) return emptyMap()
        buildMap {
            coverage(seen.values)?.let { put("gpu_time_coverage", String.format(Locale.US, "%.3f", it).toDouble()) }
            put("gpu_devices_seen", devicesSeen)
        }
    }
}
