package app.gamenative.powercontrol.drivers

/**
 * Keeps power control's GPU floor at or above the device's own min_pwrlevel. On kgsl a higher
 * sysfs level index is a lower frequency; UI levels run the other way (higher = faster).
 */
object GpuFloor {

    /**
     * The min_pwrlevel to write for [requested]: never slower than [stockFloor], the device's own
     * min_pwrlevel, unless the max_pwrlevel ceiling in force ([maxInForce]) is slower still, in which
     * case the floor follows the ceiling. Without a stock value the request is written as is.
     */
    fun sysfsMinLevel(requested: Int, stockFloor: Int?, maxInForce: Int): Int {
        if (stockFloor == null) return requested
        return minOf(requested, maxOf(stockFloor, maxInForce))
    }

    /** UI level of the stock sysfs floor, or null when either value is unknown. */
    fun uiLevel(stockFloor: Int?, numLevels: Int): Int? {
        if (stockFloor == null || numLevels <= 0) return null
        return (numLevels - 1 - stockFloor).coerceIn(0, numLevels - 1)
    }

    /** UI levels a tuner may cap the GPU at, slowest first: from [floorLevel] up to the fastest. */
    fun capSteps(numLevels: Int, floorLevel: Int?): List<Int> {
        if (numLevels <= 0) return emptyList()
        return ((floorLevel ?: 0).coerceIn(0, numLevels - 1) until numLevels).toList()
    }
}
