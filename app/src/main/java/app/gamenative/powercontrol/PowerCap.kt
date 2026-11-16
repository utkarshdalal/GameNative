package app.gamenative.powercontrol

import org.json.JSONObject

/**
 * A per-container power setting from Optimize: for each domain, how many operating points below the top of this
 * device's own table its clock cap sits. Applied at session start as a ceiling over whatever else caps the clock.
 */
object PowerCap {
    const val EXTRA = "optimizePowerCap"

    const val EFFICIENCY = "efficiency"
    const val PERFORMANCE = "performance"
    const val PRIME = "prime"
    const val GPU = "gpu"
    val DOMAINS = listOf(EFFICIENCY, PERFORMANCE, PRIME, GPU)

    private const val MAX_STEPS = 64

    /** The known domains of a steps object; null when any value is not a whole number from 0 to [MAX_STEPS]. */
    fun parseSteps(o: JSONObject): Map<String, Int>? {
        val steps = linkedMapOf<String, Int>()
        for (key in o.keys()) {
            val raw = o.opt(key)
            val n = when (raw) {
                is Int -> raw
                is Long -> raw.takeIf { it in 0..MAX_STEPS }?.toInt()
                else -> null
            } ?: return null
            if (n !in 0..MAX_STEPS) return null
            if (key in DOMAINS) steps[key] = n
        }
        return steps
    }

    fun encode(steps: Map<String, Int>): String = JSONObject().apply { steps.forEach { (k, v) -> put(k, v) } }.toString()

    fun decode(text: String?): Map<String, Int>? {
        if (text.isNullOrBlank()) return null
        return runCatching { parseSteps(JSONObject(text)) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** The value [steps] entries below the top of an ascending [table], the lowest entry at most; null for an empty table. */
    fun stepValue(table: List<Long>, steps: Int): Long? {
        if (table.isEmpty()) return null
        return table[(table.size - 1 - steps).coerceAtLeast(0)]
    }

    /** How many entries of an ascending [table] lie above [cap]. */
    fun stepsBelowTop(table: List<Long>, cap: Long): Int = table.count { it > cap }
}
