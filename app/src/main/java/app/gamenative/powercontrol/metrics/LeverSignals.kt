package app.gamenative.powercontrol.metrics

import app.gamenative.powercontrol.PowerManager
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.roundToInt

/** One CPU cluster and its kind: [CpuClusterLayout.LITTLE], [CpuClusterLayout.BIG] or [CpuClusterLayout.PRIME]. */
class CpuClusterKind(val cores: List<Int>, val kind: String)

/**
 * Which cores are little, big and prime, for hot_thread_core_share and device.cpu_clusters. Power control's own
 * grouping is used where the device has one; otherwise the layout is derived once from the cores' cpu_capacity, or
 * from cpuinfo_max_freq when a core has no capacity.
 */
object CpuClusterLayout {
    const val LITTLE = "little"
    const val BIG = "big"
    const val PRIME = "prime"

    /** With two clusters the lower one is little only below this share of the top one (the 8 Elite's 792/1024 is big). */
    private const val LITTLE_RATIO = 0.6

    @Volatile
    private var derived: List<CpuClusterKind>? = null

    /** The device's layout, low to high; null when the device tells no clusters apart. */
    fun device(): List<CpuClusterKind>? {
        runCatching { PowerManager.cpuClusterGroups() }.getOrNull()?.let { fromPowerControl(it) }?.let { return it }
        derived?.let { return it }
        val layout = runCatching { fromCapacities(readCapacities()) }.getOrNull()
            ?: runCatching {
                fromCapacities(
                    PowerTelemetry.clusterCores.zip(PowerTelemetry.clusterMaxKhz)
                        .flatMap { (cores, khz) -> cores.map { it to khz } }.toMap(),
                )
            }.getOrNull()
        derived = layout
        return layout
    }

    /** Power control's grouping, named efficiency, performance and prime. */
    fun fromPowerControl(groups: List<Pair<String, List<Int>>>): List<CpuClusterKind>? {
        val layout = groups.filter { it.second.isNotEmpty() }.mapNotNull { (name, cores) ->
            val kind = when (name.lowercase()) {
                "efficiency" -> LITTLE
                "performance" -> BIG
                "prime" -> PRIME
                else -> return@mapNotNull null
            }
            CpuClusterKind(cores.sorted(), kind)
        }
        return layout.takeIf { it.isNotEmpty() }
    }

    /**
     * Groups cores by [capacities] (cpu_capacity or max frequency per core). One group is all big. Otherwise the
     * highest is prime, the lowest is little when there are three or more groups or it is below [LITTLE_RATIO] of
     * the highest, and the rest are big.
     */
    fun fromCapacities(capacities: Map<Int, Long>): List<CpuClusterKind>? {
        val groups = capacities.filterValues { it > 0 }.entries.groupBy({ it.value }, { it.key }).toSortedMap()
        if (groups.isEmpty()) return null
        if (groups.size == 1) return listOf(CpuClusterKind(groups.values.first().sorted(), BIG))
        val top = groups.lastKey()
        val bottom = groups.firstKey()
        val lowestLittle = groups.size >= 3 || bottom < LITTLE_RATIO * top
        return groups.map { (capacity, cores) ->
            val kind = when {
                capacity == top -> PRIME
                capacity == bottom && lowestLittle -> LITTLE
                else -> BIG
            }
            CpuClusterKind(cores.sorted(), kind)
        }
    }

    fun kindOf(layout: List<CpuClusterKind>, cpu: Int): String? = layout.firstOrNull { cpu in it.cores }?.kind

    /**
     * Share of the busy seconds (the hot thread above [busyPct] of a core) on each kind of core, from the CPU each
     * second's stat said the thread last ran on. Null without a layout or without a busy second on a known core.
     */
    fun coreShare(samples: List<Pair<Int?, Int?>>, layout: List<CpuClusterKind>?, busyPct: Int = 50): Map<String, Double>? {
        if (layout.isNullOrEmpty()) return null
        val counts = HashMap<String, Int>()
        var busy = 0
        for ((pct, cpu) in samples) {
            if (pct == null || cpu == null || pct <= busyPct) continue
            val kind = kindOf(layout, cpu) ?: continue
            counts[kind] = (counts[kind] ?: 0) + 1
            busy++
        }
        if (busy == 0) return null
        return linkedMapOf(LITTLE to 0, BIG to 0, PRIME to 0).mapValues { (kind, _) ->
            ((counts[kind] ?: 0) * 100.0 / busy).roundToInt() / 100.0
        }
    }

    /** device.cpu_clusters: [{cores: [..], kind}]. */
    fun toWire(layout: List<CpuClusterKind>): List<Map<String, Any>> = layout.map { mapOf("cores" to it.cores, "kind" to it.kind) }

    private fun readCapacities(): Map<Int, Long> {
        val dirs = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) } ?: return emptyMap()
        val out = HashMap<Int, Long>()
        for (dir in dirs) {
            val cpu = dir.name.removePrefix("cpu").toIntOrNull() ?: continue
            // Every core needs a capacity, or the grouping would leave cores out.
            val capacity = SystemMetricsSources.readLongFromLine("${dir.path}/cpu_capacity") ?: return emptyMap()
            out[cpu] = capacity
        }
        return out
    }
}

/** A per-five-minute-window sum, window 0 first; windows with nothing added read 0. */
internal class WindowCounter {
    private val values = ArrayList<Long>()

    fun add(window: Int, delta: Long) {
        if (window < 0) return
        while (values.size <= window) values += 0L
        values[window] += delta.coerceAtLeast(0L)
    }

    /** Extends the series to [window] so a window without growth still reads 0. */
    fun touch(window: Int) = add(window, 0L)

    fun toList(): List<Long> = values.toList()

    fun clear() = values.clear()
}

/** The two /proc/vmstat counters the sampler follows, system-wide. */
internal object VmStat {
    const val PATH = "/proc/vmstat"

    class Reading(val majorFaults: Long, val reclaimScanned: Long)

    /** pgmajfault, and pgscan_kswapd + pgscan_direct (per-zone lines of older kernels summed; throttle events left out). */
    fun parse(lines: Sequence<String>): Reading? {
        var major = -1L
        var scanned = 0L
        var scanSeen = false
        for (line in lines) {
            val space = line.indexOf(' ')
            if (space <= 0) continue
            val name = line.substring(0, space)
            val value = line.substring(space + 1).trim().toLongOrNull() ?: continue
            when {
                name == "pgmajfault" -> major = value
                name == "pgscan_direct_throttle" -> Unit
                name.startsWith("pgscan_kswapd") || name.startsWith("pgscan_direct") -> {
                    scanned += value
                    scanSeen = true
                }
            }
        }
        if (major < 0L || !scanSeen) return null
        return Reading(major, scanned)
    }
}

/**
 * DXVK's pipeline state cache (`<exe>.dxvk-cache`). The header is "DXVK", a version and, up to version 7, a fixed
 * entry size; from version 8 each entry is a 4-byte header (stage mask in the low 8 bits, data size in the upper
 * 24), a 20-byte SHA-1 and the data.
 */
internal object DxvkStateCache {
    const val SUFFIX = ".dxvk-cache"
    const val HEADER_BYTES = 12

    /**
     * Bytes per entry used when neither the header nor the exit scan gives one: a vertex + fragment pipeline's two
     * shader keys and its packed state come to a few hundred bytes.
     */
    const val DEFAULT_ENTRY_BYTES = 256

    private const val ENTRY_HEADER_BYTES = 4
    private const val HASH_BYTES = 20
    private const val MAX_ENTRY_BYTES = 1 shl 20
    private const val TESSELLATION_STAGES = 0x2 or 0x4

    class Header(val version: Int, val entrySize: Int) {
        val variable: Boolean get() = version >= 8
    }

    fun parseHeader(bytes: ByteArray): Header? {
        if (bytes.size < HEADER_BYTES) return null
        if (bytes[0] != 'D'.code.toByte() || bytes[1] != 'X'.code.toByte() || bytes[2] != 'V'.code.toByte() || bytes[3] != 'K'.code.toByte()) {
            return null
        }
        return Header(le32(bytes, 4).toInt(), le32(bytes, 8).toInt())
    }

    fun readHeader(file: File): Header? = runCatching {
        file.inputStream().use { input ->
            val bytes = ByteArray(HEADER_BYTES)
            var read = 0
            while (read < HEADER_BYTES) {
                val n = input.read(bytes, read, HEADER_BYTES - read)
                if (n < 0) break
                read += n
            }
            parseHeader(bytes.copyOf(read))
        }
    }.getOrNull()

    /**
     * [entries] is how many entries start at or after [fromOffset] and [bytes] how many bytes they take. [tessellation]
     * is true when any entry of the file has a hull or domain stage, false when none has and the whole file was read,
     * null when the scan stopped early.
     */
    class Scan(val entries: Int, val bytes: Long, val tessellation: Boolean?)

    /** Reads a version 8+ cache from [input] (header included) once; [expired] stops it early. */
    fun scan(input: InputStream, fromOffset: Long, expired: () -> Boolean): Scan? {
        val stream = input.buffered(64 * 1024)
        val head = ByteArray(HEADER_BYTES)
        if (!readFully(stream, head)) return null
        val header = parseHeader(head) ?: return null
        if (!header.variable) return null
        var offset = HEADER_BYTES.toLong()
        var entries = 0
        var bytes = 0L
        var tessellation = false
        var complete = true
        val entryHead = ByteArray(ENTRY_HEADER_BYTES)
        var count = 0
        while (true) {
            if (++count % 256 == 0 && expired()) {
                complete = false
                break
            }
            if (!readFully(stream, entryHead)) break
            val word = le32(entryHead, 0)
            val stages = (word and 0xFF).toInt()
            val size = (word ushr 8).toInt()
            if (size <= 0 || size > MAX_ENTRY_BYTES) break
            val body = HASH_BYTES.toLong() + size
            if (!skipFully(stream, body)) break
            if ((stages and TESSELLATION_STAGES) != 0) tessellation = true
            if (offset >= fromOffset) {
                entries++
                bytes += ENTRY_HEADER_BYTES + body
            }
            offset += ENTRY_HEADER_BYTES + body
        }
        return Scan(entries, bytes, if (tessellation) true else if (complete) false else null)
    }

    /**
     * Reads a version 8+ cache [file] a piece at a time while the game runs, from where the last [advance] stopped,
     * so the exit never reads the file. An entry still being written is read on a later pass. [latest] is the scan
     * so far; its tessellation is false only once every entry to the end of the file has been read.
     */
    class Incremental(private val file: File, private val fromOffset: Long) {
        private var offset = HEADER_BYTES.toLong()
        private var headerChecked = false
        private var stopped = false
        private var entries = 0
        private var bytes = 0L
        private var tessellation = false

        @Volatile
        var latest: Scan? = null
            private set

        /** True once the last pass read to the end of the file as it was then. */
        @Volatile
        var caughtUp = false
            private set

        /** One pass from where the last one stopped; [expired] is checked every [CHECK_EVERY] entries. Call from one thread. */
        fun advance(expired: () -> Boolean) {
            if (stopped) return
            val length = file.length()
            var atEnd = false
            try {
                FileInputStream(file).use { raw ->
                    val stream = raw.buffered(64 * 1024)
                    if (!headerChecked) {
                        val head = ByteArray(HEADER_BYTES)
                        if (!readFully(stream, head)) return
                        if (parseHeader(head)?.variable != true) {
                            stopped = true
                            return
                        }
                        headerChecked = true
                    } else if (!skipFully(stream, offset)) {
                        return
                    }
                    val entryHead = ByteArray(ENTRY_HEADER_BYTES)
                    var count = 0
                    while (true) {
                        if (++count % CHECK_EVERY == 0 && expired()) break
                        if (offset + ENTRY_HEADER_BYTES > length || !readFully(stream, entryHead)) {
                            atEnd = true
                            break
                        }
                        val word = le32(entryHead, 0)
                        val stages = (word and 0xFF).toInt()
                        val size = (word ushr 8).toInt()
                        if (size <= 0 || size > MAX_ENTRY_BYTES) {
                            stopped = true
                            atEnd = true
                            break
                        }
                        val body = HASH_BYTES.toLong() + size
                        if (offset + ENTRY_HEADER_BYTES + body > length || !skipFully(stream, body)) {
                            atEnd = true
                            break
                        }
                        if ((stages and TESSELLATION_STAGES) != 0) tessellation = true
                        if (offset >= fromOffset) {
                            entries++
                            bytes += ENTRY_HEADER_BYTES + body
                        }
                        offset += ENTRY_HEADER_BYTES + body
                    }
                }
            } catch (e: Exception) {
                atEnd = false
            }
            caughtUp = atEnd
            latest = Scan(entries, bytes, if (tessellation) true else if (atEnd) false else null)
        }

        companion object {
            const val CHECK_EVERY = 16
        }
    }

    /** Bytes per new entry: the exit scan's own, else the header's fixed size, else [DEFAULT_ENTRY_BYTES]. */
    fun entryBytes(header: Header?, scan: Scan?): Double = when {
        scan != null && scan.entries > 0 && scan.bytes > 0 -> scan.bytes.toDouble() / scan.entries
        header != null && !header.variable && header.entrySize > 0 -> header.entrySize.toDouble()
        else -> DEFAULT_ENTRY_BYTES.toDouble()
    }

    /** New entries per window from the bytes the file grew by in each. */
    fun compilesByWindow(bytesByWindow: List<Long>, entryBytes: Double): List<Int> =
        bytesByWindow.map { if (entryBytes <= 0) 0 else (it / entryBytes).roundToInt() }

    private fun readFully(input: InputStream, into: ByteArray): Boolean {
        var read = 0
        while (read < into.size) {
            val n = input.read(into, read, into.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long): Boolean {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) {
                if (input.read() < 0) return false
                left--
            } else {
                left -= n
            }
        }
        return true
    }

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 3].toLong() and 0xFF) shl 24)
}
