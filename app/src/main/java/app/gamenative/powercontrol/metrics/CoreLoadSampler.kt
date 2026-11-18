package app.gamenative.powercontrol.metrics

import java.io.File

/**
 * Load of the busiest core between two calls, from the per-core lines of /proc/stat.
 * Null on the first call, after [reset], or where /proc/stat is unreadable.
 */
class CoreLoadSampler {
    private var lastTotal = LongArray(0)
    private var lastIdle = LongArray(0)

    fun reset() {
        lastTotal = LongArray(0)
        lastIdle = LongArray(0)
    }

    fun sample(): Int? {
        val lines = try {
            File("/proc/stat").bufferedReader().useLines { seq -> seq.takeWhile { it.startsWith("cpu") }.drop(1).toList() }
        } catch (_: Exception) {
            return null
        }
        return sample(lines)
    }

    internal fun sample(coreLines: List<String>): Int? {
        val parsed = coreLines.mapNotNull { line ->
            val parts = line.trim().split(WHITESPACE)
            val index = parts.firstOrNull()?.removePrefix("cpu")?.toIntOrNull() ?: return@mapNotNull null
            val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 4) return@mapNotNull null
            Triple(index, values.sum(), values[3] + values.getOrElse(4) { 0L })
        }
        if (parsed.isEmpty()) return null
        val size = parsed.maxOf { it.first } + 1
        val total = LongArray(size) { -1L }
        val idle = LongArray(size) { -1L }
        parsed.forEach { (index, t, i) ->
            total[index] = t
            idle[index] = i
        }
        val prevTotal = lastTotal
        val prevIdle = lastIdle
        lastTotal = total
        lastIdle = idle
        var max: Int? = null
        for (index in 0 until minOf(size, prevTotal.size)) {
            if (total[index] < 0L || prevTotal[index] < 0L) continue
            val totalDiff = total[index] - prevTotal[index]
            if (totalDiff <= 0L) continue
            val busy = (totalDiff - (idle[index] - prevIdle[index])).coerceIn(0L, totalDiff)
            val pct = (busy * 100L / totalDiff).toInt()
            if (max == null || pct > max) max = pct
        }
        return max
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
