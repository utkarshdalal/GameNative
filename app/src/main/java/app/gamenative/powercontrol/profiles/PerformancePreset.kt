package app.gamenative.powercontrol.profiles

/**
 * Performance preset names
 */
enum class PerformancePreset(val displayName: String) {
    POWER_SAVE("Power Save"),
    BALANCED("Balanced"),
    PERFORMANCE("Performance"),
    ON_DEMAND("On Demand"),
    WALT("WALT"),
    CUSTOM("Custom");

    companion object {
        fun fromString(name: String): PerformancePreset? {
            return entries.find { it.displayName.equals(name, ignoreCase = true) }
        }
    }
}
